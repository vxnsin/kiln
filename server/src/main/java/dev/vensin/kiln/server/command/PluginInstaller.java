package dev.vensin.kiln.server.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.zip.ZipException;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * /kiln install owner/repo takes the plugin jar from that repository's latest GitHub release; a https link to a
 * jar works as well. The download is only moved into plugins/ once it turned out to be a Kiln plugin.
 */
final class PluginInstaller {

    record Installed(String name, String version, Path file) {}

    private static final Pattern REPOSITORY = Pattern.compile("[A-Za-z0-9-]+/[A-Za-z0-9._-]+");
    private static final Pattern PLUGIN_NAME = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final Path plugins;

    PluginInstaller(Path plugins) {
        this.plugins = plugins;
    }

    Installed install(String source) throws IOException, InterruptedException {
        URI download = REPOSITORY.matcher(source).matches() ? latestJar(source) : link(source);
        Files.createDirectories(plugins);
        Path temporary = Files.createTempFile(plugins, "download-", ".part");
        try {
            HttpResponse<Path> response = client().send(request(download).timeout(Duration.ofMinutes(2)).build(),
                    HttpResponse.BodyHandlers.ofFile(temporary));
            if (response.statusCode() != 200) {
                throw new IOException("the download answered " + response.statusCode());
            }
            Map<?, ?> descriptor = descriptor(temporary);
            String name = String.valueOf(descriptor.get("name"));
            if (!PLUGIN_NAME.matcher(name).matches()) {
                throw new IOException("the plugin has no usable name in kiln-plugin.yml");
            }
            Path target = plugins.resolve(name + ".jar");
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            return new Installed(name, String.valueOf(descriptor.get("version")), target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** The first jar of the latest release that is not sources, javadoc or an API jar. */
    private URI latestJar(String repository) throws IOException, InterruptedException {
        URI api = URI.create("https://api.github.com/repos/" + repository + "/releases/latest");
        HttpResponse<String> response = client().send(request(api).timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json").build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            throw new IOException(repository + " has no release");
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub answered " + response.statusCode());
        }
        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        for (JsonElement asset : release.getAsJsonArray("assets")) {
            String name = asset.getAsJsonObject().get("name").getAsString().toLowerCase(Locale.ROOT);
            if (name.endsWith(".jar") && !name.contains("sources") && !name.contains("javadoc") && !name.contains("api")) {
                return URI.create(asset.getAsJsonObject().get("browser_download_url").getAsString());
            }
        }
        throw new IOException("the latest release of " + repository + " has no plugin jar");
    }

    private static URI link(String source) throws IOException {
        URI uri;
        try {
            uri = URI.create(source);
        } catch (IllegalArgumentException e) {
            throw new IOException("give owner/repo or a https link to a jar");
        }
        if (!"https".equals(uri.getScheme())) {
            throw new IOException("give owner/repo or a https link to a jar");
        }
        return uri;
    }

    private static Map<?, ?> descriptor(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            var entry = file.getJarEntry("kiln-plugin.yml");
            if (entry == null) {
                throw new IOException("that jar is not a Kiln plugin (no kiln-plugin.yml)");
            }
            try (InputStream in = file.getInputStream(entry)) {
                Object yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
                        .load(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!(yaml instanceof Map<?, ?> map)) {
                    throw new IOException("kiln-plugin.yml in that jar is empty");
                }
                return map;
            }
        } catch (ZipException e) {
            throw new IOException("the download is not a jar");
        }
    }

    private static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).header("User-Agent", "kiln-server");
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(TIMEOUT).build();
    }
}
