package dev.vensin.kiln.server;

import dev.vensin.kiln.server.command.Operators;
import dev.vensin.kiln.server.command.KilnCommand;
import dev.vensin.kiln.server.console.Ansi;
import dev.vensin.kiln.server.console.ConsoleInput;
import dev.vensin.kiln.server.console.InteractiveConsole;
import dev.vensin.kiln.server.console.StartReport;
import dev.vensin.kiln.server.console.Terminal;
import dev.vensin.kiln.server.plugin.PluginManager;
import dev.vensin.kiln.server.store.PlayerDataManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minestom.server.Auth;
import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
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
        MinecraftServer server = MinecraftServer.init(settings.onlineMode() ? new Auth.Online() : new Auth.Offline());
        MinecraftServer.setBrandName("Kiln");
        GlobalEventHandler events = MinecraftServer.getGlobalEventHandler();
        engine.ok(settings.onlineMode() ? "online mode" : "offline mode, accounts are not verified");

        StartReport.Step world = report.begin("World", "preparing the default world");
        if (settings.world().equals("flat")) {
            flatWorld(events);
            world.ok("flat");
        } else {
            world.ok("none, plugins provide it");
        }

        // The defaults above are registered first, so a plugin listening to the same event has the last word.
        StartReport.Step loading = report.beginQuietly("Plugins");
        Operators operators = new Operators(Path.of("ops.txt"));
        PlayerDataManager playerData = new PlayerDataManager();
        playerData.register(events);
        PluginManager plugins = new PluginManager(Path.of("data"), operators, playerData);
        plugins.loadAll(Path.of("plugins"));
        listPlugins(report, loading, plugins);

        StartReport.Step opening = report.begin("Ready", "opening port " + settings.port());
        if (!portFree(settings.port())) {
            opening.fail("port " + settings.port() + " is already in use, is another server still running?");
            report.done();
            System.exit(1);
        }
        listen(events, settings);
        KilnCommand.register(MinecraftServer.getCommandManager(), operators, plugins, version);
        MinecraftServer.getSchedulerManager().buildShutdownTask(() -> {
            plugins.disableAll();
            playerData.saveAll();
        });
        server.start(settings.host(), settings.port());
        opening.ok("port " + settings.port() + " " + Ansi.DOT + " " + report.elapsed() + " " + Ansi.DOT
                + " type kiln for help");
        report.done();
        plugins.failures().stream().filter(failure -> failure.cause() != null)
                .forEach(failure -> LOG.error("Plugin {} failed", failure.name(), failure.cause()));
        if (Terminal.interactive()) {
            InteractiveConsole.start();
        } else {
            ConsoleInput.start();
        }
    }

    private static void listPlugins(StartReport report, StartReport.Step step, PluginManager plugins) {
        List<PluginManager.Loaded> loaded = plugins.loaded();
        List<PluginManager.Failure> failures = plugins.failures();
        String summary = loaded.isEmpty() && failures.isEmpty() ? "none, drop jars into the plugins folder"
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

    private static void flatWorld(GlobalEventHandler events) {
        InstanceContainer instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        instance.setChunkSupplier(LightingChunk::new);
        instance.setGenerator(unit -> {
            unit.modifier().fillHeight(GROUND - 4, GROUND - 1, Block.STONE);
            unit.modifier().fillHeight(GROUND - 1, GROUND, Block.GRASS_BLOCK);
        });
        Pos spawn = new Pos(0.5, GROUND, 0.5);
        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(spawn);
        });
    }

    private static void listen(GlobalEventHandler events, Settings settings) {
        events.addListener(PlayerSpawnEvent.class, event -> {
            if (event.isFirstSpawn()) {
                LOG.info("{} {} joined {}", Ansi.green(Ansi.JOIN), event.getPlayer().getUsername(), online(0));
            }
        });
        events.addListener(PlayerDisconnectEvent.class, event ->
                LOG.info("{} {} left {}", Ansi.dim(Ansi.LEAVE), event.getPlayer().getUsername(), online(-1)));
        events.addListener(ServerListPingEvent.class, event -> event.setStatus(Status.builder(event.getStatus())
                .description(MiniMessage.miniMessage().deserialize(settings.motd()))
                .playerInfo(MinecraftServer.getConnectionManager().getOnlinePlayers().size(), settings.maxPlayers())
                .build()));
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
