package dev.vensin.kiln.server.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.minestom.server.MinecraftServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class PluginMessagesTest {

    @TempDir
    Path folder;

    @Test
    void fallsBackFromServerLanguageToEnglishToTheKey() throws Exception {
        Path jar = folder.resolve("plugin.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("lang/en.yml"));
            out.write("menu:\n  title: Menu\nonly-english: English\n".getBytes());
            out.putNextEntry(new JarEntry("lang/de.yml"));
            out.write("menu:\n  title: Menü\n".getBytes());
        }
        Path overrides = Files.createDirectories(folder.resolve("data/lang"));
        Files.writeString(overrides.resolve("de.yml"), "menu:\n  title: Hauptmenü\n");
        MinecraftServer.init();
        try (PluginClassLoader loader = new PluginClassLoader(jar.toUri().toURL(), getClass().getClassLoader(), List.of())) {
            PluginMessages messages = new PluginMessages(loader, folder.resolve("data"), "de", LoggerFactory.getLogger("test"));
            var console = MinecraftServer.getCommandManager().getConsoleSender();
            assertEquals("Hauptmenü", plain(messages.get(console, "menu.title")), "override beats the jar");
            assertEquals("English", plain(messages.get(console, "only-english")));
            assertEquals("missing.key", plain(messages.get(console, "missing.key")));
        }
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
