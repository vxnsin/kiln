package dev.vensin.kiln.server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.MarkedYAMLException;

public record Settings(String host, int port, boolean onlineMode, String velocitySecret, String motd,
                       String maintenanceMotd, int maxPlayers, String world, boolean watchPlugins, String name,
                       String colorFrom, String colorTo, int webPort, int stopWhenEmpty, String language,
                       String packFile, String packHost, int packPort, String packUrl, boolean packRequired,
                       String packPrompt, String storage, String redisUri, String mongoUri, String serverId,
                       String chatFormat, String joinMessage, String leaveMessage, String tabHeader, String tabFooter,
                       String publicAddress, String gameMode, boolean voidRescue, int autosave, String dailyRestart) {

    private static final String DEFAULTS = "kiln.yml";
    private static final String DEFAULT_CHAT = "<prefix><white><name></white> <dark_gray>»</dark_gray> <gray><message></gray>";
    private static final String DEFAULT_JOIN = "<dark_gray>[<green>+</green>]</dark_gray> <gray><name></gray>";
    private static final String DEFAULT_LEAVE = "<dark_gray>[<red>-</red>]</dark_gray> <gray><name></gray>";

    /** Writes the commented default file on first start, so there is something to edit. */
    public static Settings load(Path file) throws IOException {
        return load(file, System::getenv);
    }

    /**
     * Every option can be overridden by an environment variable, which is how containers configure a server:
     * {@code port} becomes {@code KILN_PORT}, {@code colors.from} becomes {@code KILN_COLORS_FROM}.
     */
    static Settings load(Path file, Function<String, String> environment) throws IOException {
        if (!Files.exists(file)) {
            try (InputStream defaults = Settings.class.getClassLoader().getResourceAsStream(DEFAULTS)) {
                Files.copy(defaults, file);
            }
        }
        Map<String, Object> yaml;
        try {
            yaml = new Yaml(new SafeConstructor(new LoaderOptions())).load(Files.readString(file, StandardCharsets.UTF_8));
        } catch (MarkedYAMLException e) {
            // SnakeYAML's message spans several lines with a drawn pointer; one line with the position is enough.
            throw new IllegalArgumentException(file.getFileName() + " line " + (e.getProblemMark().getLine() + 1) + ": "
                    + e.getProblem());
        }
        Values values = new Values(yaml == null ? Map.of() : yaml, environment);
        return new Settings(
                values.text("host", "0.0.0.0"),
                values.number("port", 25565),
                values.flag("online-mode", true),
                values.text("velocity-secret", ""),
                values.text("motd", "<gold>A Kiln server</gold>"),
                values.text("maintenance-motd", "<gold>Under maintenance</gold>"),
                values.number("max-players", 100),
                values.text("world", "flat"),
                values.flag("watch-plugins", true),
                values.text("name", "Kiln"),
                values.text("colors.from", "#ff8700"),
                values.text("colors.to", "#ffd700"),
                values.number("web-port", 0),
                values.number("stop-when-empty", 0),
                values.text("language", "en").toLowerCase(Locale.ROOT),
                values.text("resource-pack.file", "pack.zip"),
                values.text("resource-pack.host", ""),
                values.number("resource-pack.port", 8164),
                values.text("resource-pack.public-url", ""),
                values.flag("resource-pack.required", false),
                values.text("resource-pack.prompt", ""),
                values.text("storage.type", "files").toLowerCase(Locale.ROOT),
                values.text("storage.redis", "redis://localhost:6379"),
                values.text("storage.mongodb", "mongodb://localhost:27017/kiln"),
                values.text("server-id", "").isBlank() ? machineName(environment) : values.text("server-id", ""),
                values.text("chat.format", DEFAULT_CHAT),
                values.text("chat.join", DEFAULT_JOIN),
                values.text("chat.leave", DEFAULT_LEAVE),
                values.text("tab-list.header", ""),
                values.text("tab-list.footer", ""),
                values.text("public-address", ""),
                values.text("gamemode", "adventure").toLowerCase(Locale.ROOT),
                values.flag("void-rescue", true),
                values.number("autosave", 300),
                values.text("daily-restart", ""));
    }


    /** In a container the hostname is the container's id, which is unique and stable for its lifetime. */
    private static String machineName(Function<String, String> environment) {
        for (String variable : new String[] {"HOSTNAME", "COMPUTERNAME"}) {
            String name = environment.apply(variable);
            if (name != null && !name.isBlank()) {
                return name.toLowerCase(Locale.ROOT);
            }
        }
        return "server";
    }
    private record Values(Map<String, Object> yaml, Function<String, String> environment) {

        String text(String key, String fallback) {
            Object value = raw(key);
            return value == null ? fallback : String.valueOf(value);
        }

        int number(String key, int fallback) {
            Object value = raw(key);
            if (value instanceof Number number) {
                return number.intValue();
            }
            try {
                return value == null ? fallback : Integer.parseInt(String.valueOf(value).strip());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(key + " must be a whole number, not " + value);
            }
        }

        boolean flag(String key, boolean fallback) {
            Object value = raw(key);
            return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value).strip());
        }

        private Object raw(String key) {
            String fromEnvironment = environment.apply("KILN_" + key.replace('.', '_').replace('-', '_')
                    .toUpperCase(Locale.ROOT));
            if (fromEnvironment != null) {
                return fromEnvironment;
            }
            Object current = yaml;
            for (String part : key.split(Pattern.quote("."))) {
                if (!(current instanceof Map<?, ?> map)) {
                    return null;
                }
                current = map.get(part);
            }
            return current;
        }
    }
}
