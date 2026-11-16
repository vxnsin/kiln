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
                case "/metrics" -> send(exchange, 200, "text/plain; version=0.0.4", metrics().getBytes(StandardCharsets.UTF_8));
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


    /** The Prometheus text format, so Grafana and friends can chart the server without an exporter. */
    private String metrics() {
        Performance.Snapshot now = performance.snapshot();
        StringBuilder out = new StringBuilder();
        String server = "server=\"" + identity.serverId().replace("\"", "") + "\"";
        gauge(out, "kiln_ticks_per_second", "Ticks per second over the last ten seconds", server, Math.max(0, now.ticksPerSecond()));
        gauge(out, "kiln_tick_milliseconds", "Average time per tick", server, now.averageMillis());
        gauge(out, "kiln_tick_peak_milliseconds", "Slowest tick in the last ten seconds", server, now.peakMillis());
        gauge(out, "kiln_players", "Players online", server, now.players());
        gauge(out, "kiln_max_players", "Player limit", server, identity.maxPlayers());
        gauge(out, "kiln_memory_used_megabytes", "Heap in use", server, now.usedMegabytes());
        gauge(out, "kiln_memory_max_megabytes", "Heap limit", server, now.maxMegabytes());
        gauge(out, "kiln_cpu_share", "Share of all cores used by the process", server, Math.max(0, now.cpuShare()));
        gauge(out, "kiln_chunks", "Loaded chunks", server, now.chunks());
        gauge(out, "kiln_entities", "Entities in all instances", server, now.entities());
        gauge(out, "kiln_uptime_seconds", "Seconds since start", server, now.uptimeSeconds());
        out.append("# HELP kiln_plugin_tick_milliseconds Time a plugin spends per tick\n");
        out.append("# TYPE kiln_plugin_tick_milliseconds gauge\n");
        for (Performance.PluginShare plugin : now.plugins()) {
            out.append("kiln_plugin_tick_milliseconds{").append(server).append(",plugin=\"").append(plugin.name())
                    .append("\"} ").append(plugin.millisPerTick()).append('\n');
        }
        return out.toString();
    }

    private static void gauge(StringBuilder out, String name, String help, String labels, double value) {
        out.append("# HELP ").append(name).append(' ').append(help).append('\n');
        out.append("# TYPE ").append(name).append(" gauge\n");
        out.append(name).append('{').append(labels).append("} ").append(value).append('\n');
    }
    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
