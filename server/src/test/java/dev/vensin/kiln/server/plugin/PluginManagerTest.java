package dev.vensin.kiln.server.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vensin.kiln.server.command.Operators;
import dev.vensin.kiln.server.command.Permissions;
import dev.vensin.kiln.server.store.FileDriver;
import dev.vensin.kiln.server.store.PlayerDataManager;
import dev.vensin.kiln.server.network.LocalBus;
import dev.vensin.kiln.server.network.NetworkService;
import dev.vensin.kiln.server.world.WorldFiles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import net.minestom.server.MinecraftServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PluginManagerTest {

    @TempDir
    Path folder;

    @BeforeAll
    static void server() {
        MinecraftServer.init();
    }

    private PluginManager manager() throws Exception {
        Operators operators = new Operators(folder.resolve("ops.txt"));
        PluginServices services = new PluginServices(operators, new Permissions(folder.resolve("permissions.yml"), operators),
                new PlayerDataManager(), new WorldFiles(folder.resolve("worlds")), "en", new FileDriver(folder.resolve("data")),
                new NetworkService(new LocalBus(), "test", 10, ""));
        return new PluginManager(Files.createDirectories(folder.resolve("plugins")), services);
    }

    /** The class itself comes from the test classpath, since the plugin loader asks its parent first. */
    private void jar(String name, String main) throws Exception {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(folder.resolve("plugins/" + name + ".jar")))) {
            out.putNextEntry(new JarEntry("kiln-plugin.yml"));
            out.write(("name: " + name + "\nversion: 1.0.0\nmain: " + main + "\n").getBytes());
        }
    }

    @Test
    void loadsUnloadsAndCleansUpCommands() throws Exception {
        PluginManager plugins = manager();
        jar("working", TestPlugins.Working.class.getName());
        int enabled = TestPlugins.ENABLED.get();

        assertEquals(1, plugins.loadNew().size());
        assertEquals(enabled + 1, TestPlugins.ENABLED.get());
        assertTrue(MinecraftServer.getCommandManager().commandExists("working-command"));

        assertTrue(plugins.unload("working"));
        assertFalse(MinecraftServer.getCommandManager().commandExists("working-command"), "commands go with the plugin");
        assertTrue(plugins.loaded().isEmpty());
        try (var copies = Files.list(folder.resolve("plugins/.loaded"))) {
            assertEquals(0, copies.count(), "the loaded copy is deleted");
        }
    }

    @Test
    void aBrokenReplacementLeavesTheBackupToStartAgain() throws Exception {
        PluginManager plugins = manager();
        jar("working", TestPlugins.Working.class.getName());
        plugins.loadNew();
        var backup = plugins.backup("working").orElseThrow();

        plugins.unload("working");
        jar("working", TestPlugins.Broken.class.getName());
        assertTrue(plugins.loadNew().isEmpty());
        assertEquals("working", plugins.failures().getFirst().name());

        assertTrue(plugins.restore(backup));
        assertEquals("working", plugins.loaded().getFirst().info().name());
        assertFalse(Files.exists(backup.jar()), "the backup file is gone after restoring");
        plugins.disableAll();
    }

    @Test
    void reportsAMissingDependencyInsteadOfLoading() throws Exception {
        PluginManager plugins = manager();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(folder.resolve("plugins/needy.jar")))) {
            out.putNextEntry(new JarEntry("kiln-plugin.yml"));
            out.write(("name: needy\nversion: 1.0.0\nmain: x.Y\ndepends: [economy]\n").getBytes());
        }
        assertTrue(plugins.loadNew().isEmpty());
        assertTrue(plugins.failures().getFirst().reason().contains("economy"));
    }
}
