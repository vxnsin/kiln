package dev.vensin.kiln.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {

    @TempDir
    Path folder;

    @Test
    void writesTheDefaultFileAndReadsIt() throws Exception {
        Settings settings = Settings.load(folder.resolve("kiln.yml"), name -> null);
        assertTrue(Files.exists(folder.resolve("kiln.yml")));
        assertEquals(25565, settings.port());
        assertEquals("Kiln", settings.name());
        assertTrue(settings.watchPlugins());
    }

    @Test
    void environmentWinsOverTheFile() throws Exception {
        Files.writeString(folder.resolve("kiln.yml"), "port: 25570\ncolors:\n  from: \"#000000\"\n");
        Map<String, String> environment = Map.of("KILN_PORT", "25580", "KILN_COLORS_FROM", "#ff0000",
                "KILN_WATCH_PLUGINS", "false");
        Settings settings = Settings.load(folder.resolve("kiln.yml"), environment::get);
        assertEquals(25580, settings.port());
        assertEquals("#ff0000", settings.colorFrom());
        assertFalse(settings.watchPlugins());
    }

    @Test
    void namesTheOptionWhenANumberIsWrong() throws Exception {
        Files.writeString(folder.resolve("kiln.yml"), "port: lots\n");
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> Settings.load(folder.resolve("kiln.yml"), name -> null));
        assertTrue(error.getMessage().startsWith("port"));
    }

    @Test
    void namesOptionsItDoesNotKnowAndSuggestsTheClosest() throws Exception {
        Files.writeString(folder.resolve("kiln.yml"), "port: 25570\nweb_port: 8080\nchat:\n  formatt: x\n");
        Settings settings = Settings.load(folder.resolve("kiln.yml"), name -> null);
        assertEquals(List.of("chat.formatt (did you mean chat.format?)", "web_port (did you mean web-port?)"),
                settings.unknownOptions());
    }

    @Test
    void theDefaultFileHasNoUnknownOptions() throws Exception {
        assertTrue(Settings.load(folder.resolve("kiln.yml"), name -> null).unknownOptions().isEmpty());
    }
}
