package dev.vensin.kiln.server.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;

/** Serves one resource pack over HTTP and asks every joining player to load it. */
public final class ResourcePackHost {

    /** A stable id makes a resend replace the previous pack instead of stacking a second one. */
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("kiln:resource-pack".getBytes(StandardCharsets.UTF_8));

    public record Options(Path file, String host, int port, String publicUrl, boolean required, String prompt) {}

    private final Options options;
    private volatile byte[] data;
    private volatile String sha1;

    public ResourcePackHost(Options options) {
        this.options = options;
    }

    /** Reads the file again; returns the new hash. */
    public synchronized String reload() throws IOException {
        byte[] bytes = Files.readAllBytes(options.file());
        try {
            sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        data = bytes;
        return sha1;
    }

    public void start() throws IOException {
        reload();
        if (options.publicUrl().isBlank()) {
            HttpServer server = HttpServer.create(new InetSocketAddress(options.port()), 0);
            server.createContext("/", this::serve);
            server.setExecutor(Executors.newFixedThreadPool(2, Thread.ofPlatform().daemon().name("pack-", 0).factory()));
            server.start();
        }
    }

    /** The hash in the query string gets past stale HTTP caches whenever the pack changes. */
    public String url() {
        if (!options.publicUrl().isBlank()) {
            return options.publicUrl();
        }
        String host = options.host().isBlank() ? lanAddress() : options.host();
        return "http://" + host + ":" + options.port() + "/pack.zip?sha1=" + sha1;
    }

    public String size() {
        int bytes = data.length;
        return bytes < 1024 ? bytes + " B" : bytes < 1024 * 1024 ? bytes / 1024 + " KB"
                : String.format(Locale.ROOT, "%.1f MB", bytes / 1024.0 / 1024.0);
    }

    public void send(Player player) {
        ResourcePackRequest.Builder request = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url()), sha1))
                .required(options.required())
                .replace(true);
        if (!options.prompt().isBlank()) {
            request.prompt(MiniMessage.miniMessage().deserialize(options.prompt()));
        }
        player.sendResourcePacks(request.build());
    }

    public void sendToAll() {
        MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(this::send);
    }

    private void serve(HttpExchange exchange) {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod()) || !"/pack.zip".equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = data;
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException clientWentAway) {
            // the client retries on its own
        }
    }

    private static String lanAddress() {
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nic.isUp() || nic.isLoopback() || nic.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(nic.getInetAddresses())) {
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (SocketException ignored) {
            // fall through to loopback
        }
        return "127.0.0.1";
    }
}
