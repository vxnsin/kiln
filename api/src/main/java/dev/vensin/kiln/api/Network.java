package dev.vensin.kiln.api;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import net.minestom.server.entity.Player;

/**
 * Messages between Kiln servers that share a Redis, Valkey or Dragonfly (storage type redis). Without one, messages
 * only reach this server. Channels belong to the plugin: the same plugin on another server hears them.
 */
public interface Network {

    /** This server's name in the network, from server-id in kiln.yml. */
    String serverId();

    /** Sends to every server, this one included. The message becomes JSON, so records and plain classes work. */
    void publish(String channel, Object message);

    /** Handlers run on the server thread, so they can touch players and worlds directly. */
    <T> void subscribe(String channel, Class<T> type, Consumer<T> handler);

    /** Servers heard from in the last 15 seconds, this one included. */
    List<ServerInfo> servers();

    /** The server a player is on right now, if any server in the network has them. */
    Optional<String> serverOf(String playerName);

    /**
     * Moves a player to another server in the network, without a proxy, using Minecraft's transfer. Works when the
     * target has a public-address in its kiln.yml. Returns false when the server is unknown or has no address.
     */
    boolean send(Player player, String serverId);
}
