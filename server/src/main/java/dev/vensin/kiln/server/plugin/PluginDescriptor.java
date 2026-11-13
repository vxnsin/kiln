package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.PluginInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class PluginDescriptor {

    static final String FILE = "kiln-plugin.yml";
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");

    private PluginDescriptor() {}

    /** Throws with a message meant for the server owner when the jar is not a usable plugin. */
    static PluginInfo read(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            JarEntry entry = file.getJarEntry(FILE);
            if (entry == null) {
                throw new IOException("no " + FILE + " inside");
            }
            try (InputStream in = file.getInputStream(entry)) {
                Object yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
                        .load(new InputStreamReader(in, StandardCharsets.UTF_8));
                if (!(yaml instanceof Map<?, ?> map)) {
                    throw new IOException(FILE + " is empty");
                }
                String name = text(map, "name");
                String main = text(map, "main");
                if (name == null || !NAME.matcher(name).matches()) {
                    throw new IOException(FILE + " needs a name made of letters, digits, - and _");
                }
                if (main == null) {
                    throw new IOException(FILE + " needs a main class");
                }
                String version = text(map, "version");
                String description = text(map, "description");
                return new PluginInfo(name, version == null ? "?" : version, main,
                        description == null ? "" : description, list(map, "authors"), list(map, "depends"));
            } catch (RuntimeException e) {
                throw new IOException(FILE + " is not valid YAML");
            }
        }
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static List<String> list(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof List<?> items) {
            return items.stream().map(String::valueOf).toList();
        }
        return value == null ? List.of() : List.of(String.valueOf(value));
    }
}
