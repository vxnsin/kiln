package dev.vensin.kiln.server.round;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import net.minestom.server.MinecraftServer;

/** What container platforms poll to learn whether the server takes players yet. */
public final class HealthEndpoint {

    private final BooleanSupplier ready;
    private final int maxPlayers;

    public HealthEndpoint(BooleanSupplier ready, int maxPlayers) {
        this.ready = ready;
        this.maxPlayers = maxPlayers;
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/health", this::answer);
        server.setExecutor(Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("health").factory()));
        server.start();
    }

    private void answer(HttpExchange exchange) throws IOException {
        try (exchange) {
            boolean up = ready.getAsBoolean();
            int online = MinecraftServer.getConnectionManager().getOnlinePlayers().size();
            byte[] body = ("{\"status\":\"" + (up ? "ready" : "starting") + "\",\"players\":" + online
                    + ",\"maxPlayers\":" + maxPlayers + "}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(up ? 200 : 503, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
