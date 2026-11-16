package dev.vensin.kiln.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** java -jar kiln.jar health: exits 0 when the server on this machine takes players, for container health checks. */
final class HealthProbe {

    private HealthProbe() {}

    static int run() {
        String port = System.getenv().getOrDefault("KILN_WEB_PORT", "8080");
        try {
            HttpResponse<Void> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                            .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200 ? 0 : 1;
        } catch (Exception e) {
            return 1;
        }
    }
}
