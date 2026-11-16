package dev.vensin.kiln.server.command;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Kiln's own screens in the server's language. They are shown before a client has told its language, so they
 * follow the language setting in kiln.yml rather than each player.
 */
public final class ServerTexts {

    private static volatile Map<String, Object> texts = read("en");
    private static final Map<String, Object> ENGLISH = texts;

    private ServerTexts() {}

    public static void use(String language) {
        Map<String, Object> chosen = read(language);
        texts = chosen.isEmpty() ? ENGLISH : chosen;
    }

    /** A disconnect screen; {@code advice} replaces the second line when it is not blank. */
    public static Component screen(String key, String advice) {
        List<?> lines = (List<?>) value(key);
        return Chat.screen(String.valueOf(lines.get(0)), advice == null || advice.isBlank() ? String.valueOf(lines.get(1)) : advice);
    }

    public static Component screen(String key) {
        return screen(key, null);
    }

    /** The second line of a screen, for callers that add to it. */
    public static String advice(String key) {
        return String.valueOf(((List<?>) value(key)).get(1));
    }

    public static String line(String key) {
        return String.valueOf(value(key));
    }

    private static Object value(String key) {
        Object value = texts.get(key);
        return value != null ? value : ENGLISH.get(key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> read(String language) {
        try (InputStream in = ServerTexts.class.getClassLoader().getResourceAsStream("texts/" + language + ".yml")) {
            if (in == null) {
                return Map.of();
            }
            Object yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return yaml instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (IOException e) {
            return Map.of();
        }
    }
}
