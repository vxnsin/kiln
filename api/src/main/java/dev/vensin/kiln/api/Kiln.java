package dev.vensin.kiln.api;

import dev.vensin.kiln.api.store.PlayerData;
import dev.vensin.kiln.api.store.Store;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import net.minestom.server.command.CommandSender;
import org.slf4j.Logger;

/** What the server hands to a plugin. Everything else (events, commands, instances) is plain Minestom. */
public interface Kiln {

    PluginInfo info();

    Logger logger();

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
}
