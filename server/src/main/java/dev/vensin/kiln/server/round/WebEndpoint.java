package dev.vensin.kiln.server.round;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.vensin.kiln.server.command.Performance;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import net.minestom.server.MinecraftServer;

/**
 * GET /health for container platforms (200 once players can join, 503 before), GET /stats as JSON and a live
 * dashboard at /. Player names are not part of it, only counts.
 */
public final class WebEndpoint {

    private static final Gson GSON = new Gson();

    public record Identity(String name, String serverId, String version, int maxPlayers) {}

    private final BooleanSupplier ready;
    private final Identity identity;
    private final Performance performance;
    private final byte[] dashboard;

    public WebEndpoint(BooleanSupplier ready, Identity identity, Performance performance) throws IOException {
        this.ready = ready;
        this.identity = identity;
        this.performance = performance;
        try (InputStream in = WebEndpoint.class.getClassLoader().getResourceAsStream("web/dashboard.html")) {
            this.dashboard = in.readAllBytes();
        }
    }

    public void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", this::answer);
        server.setExecutor(Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("web").factory()));
        server.start();
    }

    private void answer(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            switch (path) {
                case "/health" -> health(exchange);
                case "/stats" -> send(exchange, 200, "application/json", stats().getBytes(StandardCharsets.UTF_8));
                case "/" -> send(exchange, 200, "text/html; charset=utf-8", dashboard);
                default -> exchange.sendResponseHeaders(404, -1);
            }
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        boolean up = ready.getAsBoolean();
        JsonObject body = new JsonObject();
        body.addProperty("status", up ? "ready" : "starting");
        body.addProperty("players", MinecraftServer.getConnectionManager().getOnlinePlayers().size());
        body.addProperty("maxPlayers", identity.maxPlayers());
        send(exchange, up ? 200 : 503, "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String stats() {
        JsonObject body = GSON.toJsonTree(performance.snapshot()).getAsJsonObject();
        body.addProperty("name", identity.name());
        body.addProperty("serverId", identity.serverId());
        body.addProperty("version", identity.version());
        body.addProperty("maxPlayers", identity.maxPlayers());
        body.addProperty("ready", ready.getAsBoolean());
        return body.toString();
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
