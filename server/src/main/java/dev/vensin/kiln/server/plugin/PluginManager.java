package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.Plugin;
import dev.vensin.kiln.api.PluginInfo;
import dev.vensin.kiln.server.command.Operators;
import dev.vensin.kiln.server.store.PlayerDataManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class PluginManager {

    public record Loaded(PluginInfo info, Plugin plugin, PluginClassLoader loader) {}

    /** A plugin that did not make it, with a reason short enough for one console line. */
    public record Failure(String name, String version, String reason, Throwable cause) {}

    private final Map<String, Loaded> loaded = new LinkedHashMap<>();
    private final List<Failure> failures = new ArrayList<>();

    private final Path dataRoot;
    private final Operators operators;
    private final PlayerDataManager playerData;

    public PluginManager(Path dataRoot, Operators operators, PlayerDataManager playerData) {
        this.dataRoot = dataRoot;
        this.operators = operators;
        this.playerData = playerData;
    }

    public List<Loaded> loaded() {
        return List.copyOf(loaded.values());
    }

    public List<Failure> failures() {
        return List.copyOf(failures);
    }

    public void loadAll(Path directory) throws IOException {
        Files.createDirectories(directory);
        Map<String, Path> jars = new LinkedHashMap<>();
        Map<String, PluginInfo> waiting = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path jar : files.filter(file -> file.toString().endsWith(".jar")).sorted().toList()) {
                try {
                    PluginInfo info = PluginDescriptor.read(jar);
                    if (waiting.containsKey(info.name())) {
                        fail(jar.getFileName().toString(), info.version(), "a plugin named " + info.name()
                                + " is already there", null);
                        continue;
                    }
                    waiting.put(info.name(), info);
                    jars.put(info.name(), jar);
                } catch (IOException e) {
                    fail(jar.getFileName().toString(), "?", e.getMessage(), null);
                }
            }
        }

        // Enable in dependency order: each round takes every plugin whose dependencies are already up.
        boolean progress = true;
        while (!waiting.isEmpty() && progress) {
            progress = false;
            for (PluginInfo info : List.copyOf(waiting.values())) {
                String missing = info.depends().stream()
                        .filter(name -> !loaded.containsKey(name) && !waiting.containsKey(name)).findFirst().orElse(null);
                if (missing != null) {
                    waiting.remove(info.name());
                    fail(info.name(), info.version(), "needs " + missing + ", which is not loaded", null);
                    progress = true;
                } else if (loaded.keySet().containsAll(info.depends())) {
                    waiting.remove(info.name());
                    enable(info, jars.get(info.name()), directory);
                    progress = true;
                }
            }
        }
        waiting.values().forEach(info -> fail(info.name(), info.version(), "dependency cycle with "
                + String.join(", ", info.depends()), null));
    }

    public void disableAll() {
        List<Loaded> reversed = new ArrayList<>(loaded.values());
        Collections.reverse(reversed);
        for (Loaded plugin : reversed) {
            try {
                plugin.plugin().disable();
            } catch (RuntimeException e) {
                failures.add(new Failure(plugin.info().name(), plugin.info().version(), "error while disabling", e));
            }
        }
    }

    private void enable(PluginInfo info, Path jar, Path directory) {
        try {
            List<PluginClassLoader> dependencies = info.depends().stream().map(name -> loaded.get(name).loader()).toList();
            PluginClassLoader loader = new PluginClassLoader(jar.toUri().toURL(), PluginManager.class.getClassLoader(),
                    dependencies);
            Class<?> main = Class.forName(info.main(), true, loader);
            if (!Plugin.class.isAssignableFrom(main)) {
                fail(info.name(), info.version(), info.main() + " does not implement Plugin", null);
                return;
            }
            Plugin plugin = (Plugin) main.getDeclaredConstructor().newInstance();
            plugin.enable(new PluginContext(info, loader, directory.resolve(info.name()), dataRoot.resolve(info.name()),
                    operators, playerData));
            loaded.put(info.name(), new Loaded(info, plugin, loader));
        } catch (ClassNotFoundException e) {
            fail(info.name(), info.version(), "main class " + info.main() + " not found", null);
        } catch (NoSuchMethodException e) {
            fail(info.name(), info.version(), info.main() + " needs a public constructor without arguments", null);
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError e) {
            Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            fail(info.name(), info.version(), "error while enabling: " + cause, cause);
        }
    }

    private void fail(String name, String version, String reason, Throwable cause) {
        failures.add(new Failure(name, version, reason, cause));
    }
}
