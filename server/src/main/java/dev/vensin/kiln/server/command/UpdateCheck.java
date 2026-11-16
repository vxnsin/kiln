package dev.vensin.kiln.server.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

/** Looks at the latest GitHub release. Nothing about this server is sent along. */
final class UpdateCheck {

    record Release(String version, String page, String jar) {}

    private static final URI LATEST = URI.create("https://api.github.com/repos/vxnsin/kiln/releases/latest");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private UpdateCheck() {}

    /** Empty when no release has been published yet. */
    static Optional<Release> latest() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(LATEST).timeout(TIMEOUT)
                .header("Accept", "application/vnd.github+json").header("User-Agent", "kiln-server").build();
        HttpResponse<String> response = client().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IOException("GitHub answered " + response.statusCode());
        }
        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        String jar = null;
        for (JsonElement asset : release.getAsJsonArray("assets")) {
            String url = asset.getAsJsonObject().get("browser_download_url").getAsString();
            if (url.endsWith(".jar")) {
                jar = url;
            }
        }
        String tag = release.get("tag_name").getAsString();
        // The version ends up in a file name, so it may only look like a version.
        if (!tag.matches("v?[0-9][0-9A-Za-z.-]{0,31}")) {
            throw new IOException("the latest release has an unexpected version: " + tag);
        }
        return Optional.of(new Release(tag.startsWith("v") ? tag.substring(1) : tag,
                release.get("html_url").getAsString(), jar));
    }

    static void download(Release release, Path target) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(release.jar())).timeout(Duration.ofMinutes(5))
                .header("User-Agent", "kiln-server").build();
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        HttpResponse<Path> response = client().send(request, HttpResponse.BodyHandlers.ofFile(partial));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(partial);
            throw new IOException("download answered " + response.statusCode());
        }
        Files.move(partial, target);
    }

    /** Compares dotted numbers; anything that is not a number counts as zero. */
    static boolean isNewer(String candidate, String current) {
        String[] left = candidate.split("[.-]");
        String[] right = current.split("[.-]");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            int a = number(left, i);
            int b = number(right, i);
            if (a != b) {
                return a > b;
            }
        }
        return false;
    }

    private static int number(String[] parts, int index) {
        try {
            return index < parts.length ? Integer.parseInt(parts[index]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(TIMEOUT).build();
    }
}
