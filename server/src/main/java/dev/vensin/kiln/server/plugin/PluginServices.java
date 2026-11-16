package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.server.command.Operators;
import dev.vensin.kiln.server.command.Permissions;
import dev.vensin.kiln.server.store.PlayerDataManager;
import dev.vensin.kiln.server.world.WorldFiles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** What the server shares with every plugin. Timings outlive a swap, so a plugin keeps its history. */
public record PluginServices(Operators operators, Permissions permissions, PlayerDataManager playerData,
                             WorldFiles worlds, Map<String, PluginTiming> timings) {

    public PluginServices(Operators operators, Permissions permissions, PlayerDataManager playerData, WorldFiles worlds) {
        this(operators, permissions, playerData, worlds, new ConcurrentHashMap<>());
    }

    PluginTiming timing(String plugin) {
        return timings.computeIfAbsent(plugin, name -> new PluginTiming());
    }
}
