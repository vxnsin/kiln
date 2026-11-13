package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.Kiln;
import dev.vensin.kiln.api.PluginInfo;
import dev.vensin.kiln.api.store.PlayerData;
import dev.vensin.kiln.api.store.Store;
import dev.vensin.kiln.server.command.Operators;
import dev.vensin.kiln.server.store.FileStore;
import dev.vensin.kiln.server.store.PlayerDataManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Supplier;
import net.minestom.server.command.CommandSender;
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
    private final PlayerDataManager playerData;
    private final Logger logger;

    PluginContext(PluginInfo info, PluginClassLoader loader, Path folder, Path data, Operators operators,
                  PlayerDataManager playerData) {
        this.info = info;
        this.loader = loader;
        this.folder = folder;
        this.store = new FileStore(data);
        this.operators = operators;
        this.playerData = playerData;
        this.logger = LoggerFactory.getLogger(info.name());
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
                try (InputStream in = defaults.openStream()) {
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
        return playerData.create(store.table(name, type), fresh);
    }

    @Override
    public boolean isOperator(CommandSender sender) {
        return operators.allows(sender);
    }
}
