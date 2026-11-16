package dev.vensin.kiln.api;

import dev.vensin.kiln.api.store.PlayerData;
import dev.vensin.kiln.api.store.Store;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import org.slf4j.Logger;

/** What the server hands to a plugin. Instances, entities and the rest are plain Minestom. */
public interface Kiln {

    PluginInfo info();

    Logger logger();

    /**
     * Where this plugin listens to events. Use this instead of the global event handler:
     * everything registered here disappears when the plugin is unloaded or swapped.
     */
    EventNode<Event> events();

    /** Registers commands that are removed again when the plugin is unloaded or swapped. */
    void commands(Command... commands);

    /** Worlds from the worlds folder; instances opened here are closed when the plugin is unloaded. */
    Worlds worlds();

    /** Timers that stop on their own when the plugin is unloaded or swapped. */
    Tasks tasks();

    /** plugins/&lt;name&gt;/, created on first use. */
    Path dataFolder();

    /** plugins/&lt;name&gt;/config.yml; on first start it is copied from config.yml inside the plugin jar. */
    Map<String, Object> config();

    /** Storage that belongs to this plugin only. Where it lives is the server owner's choice in kiln.yml. */
    Store store();

    /**
     * One value per player, loaded before they join and saved when they leave.
     * {@code fresh} supplies the value for a player seen for the first time.
     */
    <T> PlayerData<T> playerData(String name, Class<T> type, Supplier<T> fresh);

    /** True for the console and for players made operator with the op command. */
    boolean isOperator(CommandSender sender);

    /**
     * Whether the sender holds a permission through one of their groups in permissions.yml.
     * Operators and the console hold every permission. A trailing {@code .*} grants everything below it.
     */
    boolean hasPermission(CommandSender sender, String permission);
}
