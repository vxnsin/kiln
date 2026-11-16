package dev.vensin.kiln.server.network;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.vensin.kiln.api.ServerInfo;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Every server announces itself every few seconds, so all of them know who is where. */
public final class NetworkService {

    private static final Logger LOG = LoggerFactory.getLogger(NetworkService.class);
    private static final Gson GSON = new Gson();
    private static final String SERVERS = "kiln:servers";
    private static final String LEAVING = "kiln:leaving";
    private static final Duration HEARTBEAT = Duration.ofSeconds(5);
    private static final long FORGET_AFTER_MILLIS = 15_000;

    private record Seen(ServerInfo info, long at) {}

    private final Bus bus;
    private final String serverId;
    private final int maxPlayers;
    private final String address;
    private final Map<String, List<Consumer<JsonElement>>> handlers = new ConcurrentHashMap<>();
    private final Map<String, Seen> servers = new ConcurrentHashMap<>();

    public NetworkService(Bus bus, String serverId, int maxPlayers, String address) {
        this.bus = bus;
        this.serverId = serverId;
        this.maxPlayers = maxPlayers;
        this.address = address;
        bus.listen(this::receive);
    }

    public void start() {
        MinecraftServer.getSchedulerManager().buildTask(this::announce).repeat(HEARTBEAT).schedule();
        MinecraftServer.getSchedulerManager().buildShutdownTask(() -> {
            bus.publish(LEAVING, GSON.toJson(serverId));
            bus.close();
        });
    }

    public String serverId() {
        return serverId;
    }

    public String describe() {
        return serverId + " on " + bus.describe();
    }

    public void publish(String channel, Object message) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("from", serverId);
        envelope.add("data", GSON.toJsonTree(message));
        bus.publish(channel, envelope.toString());
    }

    public Consumer<JsonElement> subscribe(String channel, Consumer<JsonElement> handler) {
        handlers.computeIfAbsent(channel, key -> new CopyOnWriteArrayList<>()).add(handler);
        return handler;
    }

    public void unsubscribe(String channel, Consumer<JsonElement> handler) {
        List<Consumer<JsonElement>> list = handlers.get(channel);
        if (list != null) {
            list.remove(handler);
        }
    }

    public List<ServerInfo> servers() {
        long now = System.currentTimeMillis();
        servers.values().removeIf(seen -> now - seen.at() > FORGET_AFTER_MILLIS);
        servers.put(serverId, new Seen(self(), now));
        return servers.values().stream().map(Seen::info).sorted(Comparator.comparing(ServerInfo::id)).toList();
    }

    public Optional<String> serverOf(String playerName) {
        String wanted = playerName.toLowerCase(Locale.ROOT);
        return servers().stream()
                .filter(server -> server.players().stream().anyMatch(name -> name.toLowerCase(Locale.ROOT).equals(wanted)))
                .map(ServerInfo::id).findFirst();
    }


    public boolean send(Player player, String target) {
        ServerInfo server = servers().stream().filter(info -> info.id().equals(target)).findFirst().orElse(null);
        if (server == null || server.address() == null || server.address().isBlank() || target.equals(serverId)) {
            return false;
        }
        int colon = server.address().lastIndexOf(':');
        String host = colon < 0 ? server.address() : server.address().substring(0, colon);
        int port;
        try {
            port = colon < 0 ? 25565 : Integer.parseInt(server.address().substring(colon + 1));
        } catch (NumberFormatException badAddress) {
            return false;
        }
        player.getPlayerConnection().transfer(host, port);
        return true;
    }
    private ServerInfo self() {
        return new ServerInfo(serverId, address, maxPlayers, MinecraftServer.getConnectionManager().getOnlinePlayers().stream()
                .map(Player::getUsername).sorted().toList());
    }

    private void announce() {
        bus.publish(SERVERS, GSON.toJson(self()));
    }

    /** Messages arrive on a network thread; plugin handlers run on the next tick instead. */
    private void receive(String channel, String json) {
        try {
            if (channel.equals(SERVERS)) {
                ServerInfo info = GSON.fromJson(json, ServerInfo.class);
                servers.put(info.id(), new Seen(info, System.currentTimeMillis()));
                return;
            }
            if (channel.equals(LEAVING)) {
                servers.remove(GSON.fromJson(json, String.class));
                return;
            }
            List<Consumer<JsonElement>> list = handlers.get(channel);
            if (list == null || list.isEmpty()) {
                return;
            }
            JsonElement data = JsonParser.parseString(json).getAsJsonObject().get("data");
            MinecraftServer.getSchedulerManager().scheduleNextTick(() -> list.forEach(handler -> {
                try {
                    handler.accept(data);
                } catch (RuntimeException e) {
                    LOG.warn("Handler for {} failed", channel, e);
                }
            }));
        } catch (RuntimeException e) {
            LOG.warn("Unreadable message on {}: {}", channel, e.getMessage());
        }
    }
}
