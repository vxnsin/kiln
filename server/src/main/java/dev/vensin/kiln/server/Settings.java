package dev.vensin.kiln.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public record Settings(String host, int port, boolean onlineMode, String velocitySecret, String motd,
                       String maintenanceMotd, int maxPlayers, String world, boolean watchPlugins, String name,
                       String colorFrom, String colorTo) {

    private static final String DEFAULTS = "kiln.yml";

    /** Writes the commented default file on first start, so there is something to edit. */
    public static Settings load(Path file) throws IOException {
        if (!Files.exists(file)) {
            try (InputStream defaults = Settings.class.getClassLoader().getResourceAsStream(DEFAULTS)) {
                Files.copy(defaults, file);
            }
        }
        Map<String, Object> yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
                .load(Files.readString(file, StandardCharsets.UTF_8));
        Map<String, Object> root = yaml == null ? Map.of() : yaml;
        Map<String, Object> colors = root.get("colors") instanceof Map<?, ?> map ? section(map) : Map.of();
        return new Settings(
                text(root, "host", "0.0.0.0"),
                number(root, "port", 25565),
                !Boolean.FALSE.equals(root.get("online-mode")),
                text(root, "velocity-secret", ""),
                text(root, "motd", "<gold>A Kiln server</gold>"),
                text(root, "maintenance-motd", "<gold>Under maintenance</gold>"),
                number(root, "max-players", 100),
                text(root, "world", "flat"),
                !Boolean.FALSE.equals(root.get("watch-plugins")),
                text(root, "name", "Kiln"),
                text(colors, "from", "#ff8700"),
                text(colors, "to", "#ffd700"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> section(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static int number(Map<String, Object> map, String key, int fallback) {
        return map.get(key) instanceof Number number ? number.intValue() : fallback;
    }

    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
