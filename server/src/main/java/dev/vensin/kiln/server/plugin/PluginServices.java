package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.server.command.Operators;
import dev.vensin.kiln.server.command.Permissions;
import dev.vensin.kiln.server.network.NetworkService;
import dev.vensin.kiln.server.store.PlayerDataManager;
import dev.vensin.kiln.server.store.StorageDriver;
import dev.vensin.kiln.server.world.WorldFiles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** What the server shares with every plugin. Timings outlive a swap, so a plugin keeps its history. */
public record PluginServices(Operators operators, Permissions permissions, PlayerDataManager playerData,
                             WorldFiles worlds, String language, StorageDriver storage, NetworkService network,
                             Map<String, PluginTiming> timings) {

    public PluginServices(Operators operators, Permissions permissions, PlayerDataManager playerData, WorldFiles worlds,
                          String language, StorageDriver storage, NetworkService network) {
        this(operators, permissions, playerData, worlds, language, storage, network, new ConcurrentHashMap<>());
    }

    PluginTiming timing(String plugin) {
        return timings.computeIfAbsent(plugin, name -> new PluginTiming());
    }
}
