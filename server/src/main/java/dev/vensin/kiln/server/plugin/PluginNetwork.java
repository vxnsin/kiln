package dev.vensin.kiln.server.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import dev.vensin.kiln.api.Network;
import dev.vensin.kiln.api.ServerInfo;
import dev.vensin.kiln.server.network.NetworkService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minestom.server.entity.Player;

/** Channels are prefixed with the plugin name, so two plugins never hear each other by accident. */
final class PluginNetwork implements Network {

    private static final Gson GSON = new Gson();

    private final NetworkService service;
    private final String prefix;
    private final PluginTiming timing;
    private final Map<Consumer<JsonElement>, String> subscriptions = new ConcurrentHashMap<>();

    PluginNetwork(NetworkService service, String plugin, PluginTiming timing) {
        this.service = service;
        this.prefix = "plugin:" + plugin + ":";
        this.timing = timing;
    }

    @Override
    public String serverId() {
        return service.serverId();
    }

    @Override
    public void publish(String channel, Object message) {
        service.publish(prefix + channel, message);
    }

    @Override
    public <T> void subscribe(String channel, Class<T> type, Consumer<T> handler) {
        Consumer<JsonElement> wrapped = service.subscribe(prefix + channel, data -> {
            long start = System.nanoTime();
            try {
                handler.accept(GSON.fromJson(data, type));
            } finally {
                timing.add(System.nanoTime() - start);
            }
        });
        subscriptions.put(wrapped, prefix + channel);
    }

    @Override
    public List<ServerInfo> servers() {
        return service.servers();
    }

    @Override
    public Optional<String> serverOf(String playerName) {
        return service.serverOf(playerName);
    }

    @Override
    public boolean send(Player player, String serverId) {
        return service.send(player, serverId);
    }

    void close() {
        subscriptions.forEach((handler, channel) -> service.unsubscribe(channel, handler));
        subscriptions.clear();
    }
}
