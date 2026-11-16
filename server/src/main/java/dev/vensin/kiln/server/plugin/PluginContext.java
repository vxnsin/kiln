package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.Events;
import dev.vensin.kiln.api.Kiln;
import dev.vensin.kiln.api.PluginInfo;
import dev.vensin.kiln.api.Tasks;
import dev.vensin.kiln.api.Worlds;
import dev.vensin.kiln.api.store.PlayerData;
import dev.vensin.kiln.api.store.Store;
import dev.vensin.kiln.server.command.Operators;
import dev.vensin.kiln.server.command.Permissions;
import dev.vensin.kiln.server.store.FileStore;
import dev.vensin.kiln.server.store.PlayerDataManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.event.Event;
import net.minestom.server.event.EventNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class PluginContext implements Kiln {

    private static final String CONFIG = "config.yml";

    private final PluginInfo info;
    private final PluginClassLoader loader;
    private final Path folder;
    private final Store store;
    private final Operators operators;
    private final Permissions permissions;
    private final PlayerDataManager playerData;
    private final Logger logger;
    private final EventNode<Event> node;
    private final PluginEvents events;
    private final PluginTiming timing;
    private final List<Command> commands = new ArrayList<>();
    private final List<PlayerData<?>> data = new ArrayList<>();
    private final PluginWorlds worlds;
    private final PluginTasks tasks;

    PluginContext(PluginInfo info, PluginClassLoader loader, Path folder, Path data, PluginServices services) {
        this.info = info;
        this.loader = loader;
        this.folder = folder;
        this.store = new FileStore(data);
        this.operators = services.operators();
        this.permissions = services.permissions();
        this.playerData = services.playerData();
        this.worlds = new PluginWorlds(services.worlds());
        this.timing = services.timing(info.name());
        this.tasks = new PluginTasks(timing);
        this.logger = LoggerFactory.getLogger(info.name());
        this.node = EventNode.all("plugin-" + info.name());
        this.events = new PluginEvents(node, timing);
        MinecraftServer.getGlobalEventHandler().addChild(node);
    }

    @Override
    public PluginInfo info() {
        return info;
    }

    @Override
    public Logger logger() {
        return logger;
    }


    @Override
    public Events events() {
        return events;
    }

    @Override
    public void commands(Command... added) {
        MinecraftServer.getCommandManager().register(added);
        commands.addAll(List.of(added));
    }

    @Override
    public Worlds worlds() {
        return worlds;
    }

    @Override
    public Tasks tasks() {
        return tasks;
    }

    /** Takes back everything the plugin registered through this context. */
    void close() {
        tasks.cancelAll();
        worlds.closeAll();
        MinecraftServer.getGlobalEventHandler().removeChild(node);
        commands.forEach(MinecraftServer.getCommandManager()::unregister);
        commands.clear();
        data.forEach(playerData::remove);
        data.clear();
    }
    @Override
    public Path dataFolder() {
        try {
            return Files.createDirectories(folder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> config() {
        Path file = dataFolder().resolve(CONFIG);
        try {
            // findResource looks in the plugin jar only, never in the server or in other plugins.
            URL defaults = loader.findResource(CONFIG);
            if (!Files.exists(file) && defaults != null) {
                // Without this the JVM caches the open jar and the file can never be replaced or deleted.
                URLConnection connection = defaults.openConnection();
                connection.setUseCaches(false);
                try (InputStream in = connection.getInputStream()) {
                    Files.copy(in, file);
                }
            }
            if (!Files.exists(file)) {
                return Map.of();
            }
            Object yaml = new Yaml(new SafeConstructor(new LoaderOptions()))
                    .load(Files.readString(file, StandardCharsets.UTF_8));
            return yaml instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Store store() {
        return store;
    }

    @Override
    public <T> PlayerData<T> playerData(String name, Class<T> type, Supplier<T> fresh) {
        PlayerData<T> created = playerData.create(store.table(name, type), fresh);
        data.add(created);
        return created;
    }

    @Override
    public boolean isOperator(CommandSender sender) {
        return operators.allows(sender);
    }

    @Override
    public boolean hasPermission(CommandSender sender, String permission) {
        return permissions.has(sender, permission);
    }
}
