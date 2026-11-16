package dev.vensin.kiln.server;

import dev.vensin.kiln.server.command.Operators;
import dev.vensin.kiln.server.command.Performance;
import dev.vensin.kiln.server.command.Permissions;
import dev.vensin.kiln.server.chat.ChatStyle;
import dev.vensin.kiln.server.command.Chat;
import dev.vensin.kiln.server.command.KilnCommand;
import dev.vensin.kiln.server.command.Maintenance;
import dev.vensin.kiln.server.console.Ansi;
import dev.vensin.kiln.server.console.ConsoleInput;
import dev.vensin.kiln.server.console.InteractiveConsole;
import dev.vensin.kiln.server.console.StartReport;
import dev.vensin.kiln.server.console.Terminal;
import dev.vensin.kiln.server.network.LocalBus;
import dev.vensin.kiln.server.network.NetworkService;
import dev.vensin.kiln.server.network.RedisBus;
import dev.vensin.kiln.server.pack.ResourcePackHost;
import dev.vensin.kiln.server.plugin.PluginManager;
import dev.vensin.kiln.server.plugin.PluginServices;
import dev.vensin.kiln.server.plugin.PluginWatcher;
import dev.vensin.kiln.server.round.EmptyShutdown;
import dev.vensin.kiln.server.round.WebEndpoint;
import dev.vensin.kiln.server.store.FileDriver;
import dev.vensin.kiln.server.store.MongoDriver;
import dev.vensin.kiln.server.store.RedisDriver;
import dev.vensin.kiln.server.store.StorageDriver;
import dev.vensin.kiln.server.store.PlayerDataManager;
import dev.vensin.kiln.server.world.WorldFiles;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.server.ServerListPingEvent;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.block.Block;
import net.minestom.server.ping.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class Server {

    private static final Logger LOG = LoggerFactory.getLogger(Server.class);
    private static final int GROUND = 40;
    private static final String STARTING_MOTD =
            "<gradient:#ff8700:#ffd700><bold>Starting up</bold></gradient><newline><gray>ready in a few seconds</gray>";

    private Server() {}

    static void run() {
        StartReport report = new StartReport(System.out);
        Properties build = buildInfo();
        report.header(build.getProperty("version", "dev"), build.getProperty("minecraft", "?"));
        try {
            start(report, build.getProperty("version", "dev"));
        } catch (IOException | RuntimeException e) {
            report.fail("Stopped", e.getMessage() == null ? e.toString() : e.getMessage());
            report.done();
            LOG.error("Startup failed", e);
            System.exit(1);
        }
    }

    private static void start(StartReport report, String version) throws IOException {
        StartReport.Step engine = report.begin("Engine", "starting Minestom");
        Settings settings = Settings.load(Path.of("kiln.yml"));
        Chat.brand(settings.name(), settings.colorFrom(), settings.colorTo());
        if (!portFree(settings.port())) {
            engine.fail("port " + settings.port() + " is already in use, is another server still running?");
            report.done();
            System.exit(1);
        }
        boolean proxied = !settings.velocitySecret().isBlank();
        Auth auth = proxied ? new Auth.Velocity(settings.velocitySecret())
                : settings.onlineMode() ? new Auth.Online() : new Auth.Offline();
        MinecraftServer server = MinecraftServer.init(auth);
        MinecraftServer.setBrandName("Kiln");
        StorageDriver storage;
        try {
            storage = storage(settings);
        } catch (IOException | RuntimeException e) {
            engine.fail(settings.storage() + " storage is not reachable: " + e.getMessage());
            report.done();
            System.exit(1);
            return;
        }
        GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();
        Performance performance = new Performance();
        performance.register(events);

        // The port opens right away: whoever tries during startup gets a clear answer instead of a timeout.
        AtomicBoolean ready = new AtomicBoolean();
        Operators operators = new Operators(Path.of("ops.txt"));
        Permissions permissions = new Permissions(Path.of("permissions.yml"), operators);
        Maintenance maintenance = new Maintenance(
                storage.forPlugin("kiln").table("server", Maintenance.State.class), operators);
        events.addListener(AsyncPlayerPreLoginEvent.class, event -> {
            if (!ready.get()) {
                event.getConnection().kick(Chat.screen("The server is still starting", "Give it a few seconds and join again."));
            } else if (!maintenance.lets(event.getGameProfile().name())) {
                event.getConnection().kick(maintenance.screen());
            }
        });
        listen(events, settings, ready, maintenance);
        new ChatStyle(new ChatStyle.Templates(settings.chatFormat(), settings.joinMessage(), settings.leaveMessage(),
                settings.tabHeader(), settings.tabFooter(), settings.name(), settings.serverId(), settings.maxPlayers()),
                permissions).register(events);
        if (settings.webPort() > 0) {
            new WebEndpoint(ready::get, new WebEndpoint.Identity(settings.name(), settings.serverId(), version,
                    settings.maxPlayers()), performance).start(settings.webPort());
        }
        server.start(settings.host(), settings.port());
        engine.ok((proxied ? "behind Velocity" : settings.onlineMode() ? "online mode"
                : "offline mode, accounts are not verified") + " "
                + Ansi.DOT + " port " + settings.port());

        report.beginQuietly("Storage").ok(storage.describe());
        NetworkService network = new NetworkService(storage instanceof RedisDriver redis ? new RedisBus(redis.uri())
                : new LocalBus(), settings.serverId(), settings.maxPlayers());
        report.beginQuietly("Network").ok(network.describe());

        StartReport.Step world = report.begin("World", "preparing the default world");
        WorldFiles worlds = new WorldFiles(Path.of("worlds"));
        Files.createDirectories(Path.of("worlds"));
        switch (settings.world()) {
            case "flat" -> {
                defaultWorld(events, worlds, flatWorld(), new Pos(0.5, GROUND, 0.5));
                world.ok("flat");
            }
            case "none" -> world.ok("none, plugins provide it");
            default -> {
                if (!worlds.available().contains(settings.world())) {
                    world.fail("worlds/" + settings.world() + " is not there or holds no Minecraft world");
                    report.done();
                    System.exit(1);
                }
                defaultWorld(events, worlds, worlds.open(settings.world()), worlds.spawn(settings.world()));
                world.ok(settings.world() + " from worlds/" + settings.world());
            }
        }

        StartReport.Step packing = report.begin("Pack", "preparing the resource pack");
        ResourcePackHost pack = null;
        Path packFile = Path.of(settings.packFile());
        if (settings.packFile().isBlank() || !Files.isRegularFile(packFile)) {
            packing.ok("none, drop " + (settings.packFile().isBlank() ? "pack.zip" : settings.packFile()) + " next to the server");
        } else {
            pack = new ResourcePackHost(new ResourcePackHost.Options(packFile, settings.packHost(), settings.packPort(),
                    settings.packUrl(), settings.packRequired(), settings.packPrompt()));
            try {
                pack.start();
                ResourcePackHost host = pack;
                events.addListener(AsyncPlayerConfigurationEvent.class, event -> host.send(event.getPlayer()));
                packing.ok(host.size() + " " + Ansi.DOT + " " + host.url());
            } catch (IOException e) {
                packing.warn("could not serve " + settings.packFile() + ": " + e.getMessage());
                pack = null;
            }
        }

        // The defaults above are registered first, so a plugin listening to the same event has the last word.
        StartReport.Step loading = report.beginQuietly("Plugins");
        PlayerDataManager playerData = new PlayerDataManager();
        playerData.register(events);
        PluginServices services = new PluginServices(operators, permissions, playerData, worlds, settings.language(),
                storage, network);
        PluginManager plugins = new PluginManager(Path.of("plugins"), services);
        performance.plugins(services.timings(), () -> plugins.loaded().stream().map(loaded -> loaded.info().name()).toList());
        plugins.loadNew();
        listPlugins(report, loading, plugins);

        StartReport.Step opening = report.begin("Ready", "letting players in");
        KilnCommand kiln = KilnCommand.register(MinecraftServer.getCommandManager(), operators, permissions, plugins,
                performance, maintenance, version, pack, network, worlds);
        if (settings.watchPlugins()) {
            watch(plugins, kiln);
        }
        MinecraftServer.getSchedulerManager().buildShutdownTask(() -> {
            plugins.disableAll();
            playerData.saveAll();
            storage.close();
        });
        if (settings.stopWhenEmpty() > 0) {
            new EmptyShutdown(Duration.ofSeconds(settings.stopWhenEmpty())).register(events);
        }
        network.start();
        ready.set(true);
        opening.ok(report.elapsed() + " " + Ansi.DOT + " type kiln for help");
        report.done();
        plugins.failures().stream().filter(failure -> failure.cause() != null)
                .forEach(failure -> LOG.error("Plugin {} failed", failure.name(), failure.cause()));
        if (Terminal.interactive()) {
            InteractiveConsole.start();
        } else {
            ConsoleInput.start();
        }
    }


    private static void watch(PluginManager plugins, KilnCommand kiln) throws IOException {
        var console = MinecraftServer.getCommandManager().getConsoleSender();
        var scheduler = MinecraftServer.getSchedulerManager();
        new PluginWatcher(Path.of("plugins"), jar -> scheduler.scheduleNextTick(() -> {
            String name = plugins.nameOf(jar);
            if (name == null) {
                kiln.load(console);
            } else if (!plugins.isCurrent(jar)) {
                kiln.reload(console, name);
            }
        }), jar -> scheduler.scheduleNextTick(() -> {
            String name = plugins.nameOf(jar);
            if (name != null) {
                kiln.unload(console, name, true);
            }
        })).start();
    }
    private static void listPlugins(StartReport report, StartReport.Step step, PluginManager plugins) {
        List<PluginManager.Loaded> loaded = plugins.loaded();
        List<PluginManager.Failure> failures = plugins.failures();
        String summary = loaded.isEmpty() && failures.isEmpty() ? "none, drop jars into the plugins folder and type kiln load"
                : loaded.size() + " loaded";
        if (failures.isEmpty()) {
            step.ok(summary);
        } else {
            step.warn(summary + ", " + failures.size() + " failed");
        }
        int width = 0;
        for (PluginManager.Loaded plugin : loaded) {
            width = Math.max(width, plugin.info().name().length());
        }
        for (PluginManager.Failure failure : failures) {
            width = Math.max(width, failure.name().length());
        }
        int total = loaded.size() + failures.size();
        int index = 0;
        for (PluginManager.Loaded plugin : loaded) {
            report.detail(Ansi.dim(branch(++index == total)) + " " + pad(plugin.info().name(), width) + "  "
                    + Ansi.dim(plugin.info().version()));
        }
        for (PluginManager.Failure failure : failures) {
            report.detail(Ansi.dim(branch(++index == total)) + " " + Ansi.red(pad(failure.name(), width)) + "  "
                    + Ansi.dim(failure.version()) + "  " + Ansi.red(Ansi.ERROR + " " + failure.reason()));
        }
    }

    private static String branch(boolean last) {
        if (!Ansi.UNICODE) {
            return "|-";
        }
        return last ? "└─" : "├─";
    }

    private static String pad(String text, int width) {
        return String.format("%-" + width + "s", text);
    }

    private static InstanceContainer flatWorld() {
        InstanceContainer instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        instance.setChunkSupplier(LightingChunk::new);
        instance.setGenerator(unit -> {
            unit.modifier().fillHeight(GROUND - 4, GROUND - 1, Block.STONE);
            unit.modifier().fillHeight(GROUND - 1, GROUND, Block.GRASS_BLOCK);
        });
        return instance;
    }

    private static void defaultWorld(GlobalEventHandler events, WorldFiles worlds, InstanceContainer instance, Pos spawn) {
        worlds.home(instance, spawn);
        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(spawn);
        });
    }

    private static void listen(GlobalEventHandler events, Settings settings, AtomicBoolean ready,
                               Maintenance maintenance) {
        events.addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn()) {
                LOG.info("{} {} joined {}", Ansi.green(Ansi.JOIN), event.getPlayer().getUsername(), online(0));
            }
        });
        events.addListener(PlayerDisconnectEvent.class, event ->
                LOG.info("{} {} left {}", Ansi.dim(Ansi.LEAVE), event.getPlayer().getUsername(), online(-1)));
        events.addListener(ServerListPingEvent.class, event -> event.setStatus(Status.builder(event.getStatus())
                .description(MiniMessage.miniMessage().deserialize(!ready.get() ? STARTING_MOTD
                        : maintenance.enabled() ? settings.maintenanceMotd() : settings.motd()))
                .playerInfo(MinecraftServer.getConnectionManager().getOnlinePlayers().size(), settings.maxPlayers())
                .build()));
    }


    private static StorageDriver storage(Settings settings) throws IOException {
        return switch (settings.storage()) {
            case "files" -> new FileDriver(Path.of("data"));
            case "redis" -> new RedisDriver(URI.create(settings.redisUri()));
            case "mongodb" -> new MongoDriver(settings.mongoUri());
            default -> throw new IllegalArgumentException("storage.type must be files, redis or mongodb");
        };
    }
    private static boolean portFree(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            return true;
        } catch (IOException taken) {
            return false;
        }
    }

    private static String online(int offset) {
        int count = MinecraftServer.getConnectionManager().getOnlinePlayers().size() + offset;
        return Ansi.dim("(" + count + " online)");
    }

    private static Properties buildInfo() {
        Properties properties = new Properties();
        try (InputStream in = Server.class.getClassLoader().getResourceAsStream("version.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException ignored) {
            // the header falls back to placeholders
        }
        return properties;
    }
}
