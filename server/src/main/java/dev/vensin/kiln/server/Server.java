package dev.vensin.kiln.server;

import dev.vensin.kiln.server.command.Operators;
import dev.vensin.kiln.server.command.Performance;
import dev.vensin.kiln.server.command.Permissions;
import dev.vensin.kiln.server.command.ServerTexts;
import dev.vensin.kiln.server.chat.ChatStyle;
import dev.vensin.kiln.server.command.Bans;
import dev.vensin.kiln.server.command.Chat;
import dev.vensin.kiln.server.command.KilnCommand;
import dev.vensin.kiln.server.command.Maintenance;
import dev.vensin.kiln.server.console.Ansi;
import dev.vensin.kiln.server.console.ConsoleInput;
import dev.vensin.kiln.server.console.InteractiveConsole;
import dev.vensin.kiln.server.console.KilnIcon;
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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.entity.GameMode;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.Instance;
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
    private static final int VOID_MARGIN = 64;

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
        ServerTexts.use(settings.language());
        if (!portFree(settings.port())) {
            engine.fail("port " + settings.port() + " is already in use, is another server still running?");
            report.done();
            System.exit(1);
        }
        boolean proxied = !settings.velocitySecret().isBlank();
        if (!proxied && !settings.publicAddress().isBlank()) {
            // Behind a proxy every player must come through it, so direct transfers stay closed there.
            // Read once when Minestom starts, so it has to be in place before anything of Minestom loads.
            System.setProperty("minestom.accept-transfers", "true");
        }
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
        Bans bans = new Bans(storage.forPlugin("kiln").table("bans", Bans.Ban.class));
        Maintenance maintenance = new Maintenance(
                storage.forPlugin("kiln").table("server", Maintenance.State.class), operators);
        events.addListener(AsyncPlayerPreLoginEvent.class, event -> {
            String name = event.getGameProfile().name();
            if (!ready.get()) {
                event.getConnection().kick(ServerTexts.screen("starting"));
                return;
            }
            // A check that cannot be made keeps the player out: a storage hiccup must not let a banned player in.
            try {
                Optional<Bans.Ban> ban = bans.active(name);
                if (ban.isPresent()) {
                    event.getConnection().kick(Bans.screen(ban.get()));
                } else if (!maintenance.lets(name)) {
                    event.getConnection().kick(maintenance.screen());
                } else if (MinecraftServer.getConnectionManager().getOnlinePlayers().size() >= settings.maxPlayers()
                        && !operators.isOperator(name)) {
                    event.getConnection().kick(ServerTexts.screen("full"));
                }
            } catch (RuntimeException e) {
                LOG.error("Could not check whether {} may join, keeping them out: {}", name, e.getMessage());
                event.getConnection().kick(ServerTexts.screen("unchecked"));
            }
        });
        AtomicReference<Settings> live = new AtomicReference<>(settings);
        listen(events, live, ready, maintenance);
        ChatStyle chatStyle = new ChatStyle(ChatStyle.from(settings), permissions);
        chatStyle.register(events);
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
                : new LocalBus(), settings.serverId(), settings.maxPlayers(), settings.publicAddress());
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
                performance, maintenance, version, pack, network, worlds, bans);
        kiln.onConfigReload(() -> {
            Settings fresh = Settings.load(Path.of("kiln.yml"));
            Chat.brand(fresh.name(), fresh.colorFrom(), fresh.colorTo());
            ServerTexts.use(fresh.language());
            chatStyle.update(ChatStyle.from(fresh));
            live.set(fresh);
        });
        if (!settings.dailyRestart().isBlank()) {
            kiln.dailyStop(settings.dailyRestart());
        }
        if (settings.watchPlugins()) {
            watch(plugins, kiln);
        }
        MinecraftServer.getSchedulerManager().buildShutdownTask(() -> {
            for (Runnable step : List.<Runnable>of(plugins::disableAll, playerData::saveAll, storage::close)) {
                try {
                    step.run();
                } catch (RuntimeException e) {
                    LOG.error("Shutdown step failed, going on with the next", e);
                }
            }
        });
        if (settings.autosave() > 0) {
            // Saving talks to storage, which can be slow, so it runs on its own thread instead of the tick.
            Thread.ofPlatform().daemon().name("autosave").start(() -> {
                while (true) {
                    try {
                        Thread.sleep(Duration.ofSeconds(settings.autosave()));
                    } catch (InterruptedException e) {
                        return;
                    }
                    playerData.saveAll();
                }
            });
        }
        if (settings.stopWhenEmpty() > 0) {
            new EmptyShutdown(Duration.ofSeconds(settings.stopWhenEmpty())).register(events);
        }
        network.start();
        ready.set(true);
        opening.ok(report.elapsed() + " " + Ansi.DOT + " type kiln for help");
        LoggerFactory.getLogger("kiln.startup").info("Kiln {} ready on port {} in {}; storage {}; plugins: {}{}", version,
                settings.port(), report.elapsed(), storage.describe(),
                plugins.loaded().stream().map(p -> p.info().name() + " " + p.info().version()).toList(),
                plugins.failures().isEmpty() ? "" : "; failed: " + plugins.failures().stream()
                        .map(f -> f.name() + " (" + f.reason() + ")").toList());
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

    private static void listen(GlobalEventHandler events, AtomicReference<Settings> live, AtomicBoolean ready,
                               Maintenance maintenance) {
        GameMode joinMode = switch (live.get().gameMode()) {
            case "survival" -> GameMode.SURVIVAL;
            case "creative" -> GameMode.CREATIVE;
            case "spectator" -> GameMode.SPECTATOR;
            default -> GameMode.ADVENTURE;
        };
        events.addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn()) {
                event.getPlayer().setGameMode(joinMode);
            }
        });
        if (live.get().voidRescue()) {
            events.addListener(PlayerMoveEvent.class, event -> {
                Instance instance = event.getPlayer().getInstance();
                if (instance != null && event.getNewPosition().y() < instance.getCachedDimensionType().minY() - VOID_MARGIN) {
                    event.setNewPosition(event.getPlayer().getRespawnPoint());
                }
            });
        }
        events.addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn()) {
                LOG.info("{} {} joined {}", Ansi.green(Ansi.JOIN), event.getPlayer().getUsername(), online(0));
            }
        });
        events.addListener(PlayerDisconnectEvent.class, event ->
                LOG.info("{} {} left {}", Ansi.dim(Ansi.LEAVE), event.getPlayer().getUsername(), online(-1)));
        byte[] icon = serverIcon();
        events.addListener(ServerListPingEvent.class, event -> {
            Status.Builder status = Status.builder(event.getStatus())
                    .description(MiniMessage.miniMessage().deserialize(!ready.get() ? ServerTexts.line("starting-motd")
                            : maintenance.enabled() ? live.get().maintenanceMotd() : live.get().motd()))
                    .playerInfo(MinecraftServer.getConnectionManager().getOnlinePlayers().size(), live.get().maxPlayers());
            if (icon != null) {
                status.favicon(icon);
            }
            event.setStatus(status.build());
        });
    }


    private static StorageDriver storage(Settings settings) throws IOException {
        return switch (settings.storage()) {
            case "files" -> new FileDriver(Path.of("data"));
            case "redis" -> new RedisDriver(URI.create(settings.redisUri()));
            case "mongodb" -> new MongoDriver(settings.mongoUri());
            default -> throw new IllegalArgumentException("storage.type must be files, redis or mongodb");
        };
    }

    /** server-icon.png next to the server, 64 by 64 pixels like vanilla expects; without one, the kiln. */
    private static byte[] serverIcon() {
        Path file = Path.of("server-icon.png");
        if (!Files.isRegularFile(file)) {
            return KilnIcon.png();
        }
        try {
            byte[] png = Files.readAllBytes(file);
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null || image.getWidth() != 64 || image.getHeight() != 64) {
                LOG.warn("server-icon.png must be a 64 x 64 PNG, it is ignored");
                return null;
            }
            return png;
        } catch (IOException e) {
            LOG.warn("server-icon.png could not be read: {}", e.getMessage());
            return null;
        }
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
