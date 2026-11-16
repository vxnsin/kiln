package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.Plugin;
import dev.vensin.kiln.api.PluginInfo;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class PluginManager {

    public record Loaded(PluginInfo info, Plugin plugin, PluginClassLoader loader, PluginContext context, Path source,
                         Path copy) {}

    /** A plugin that did not make it, with a reason short enough for one console line. */
    public record Failure(String name, String version, String reason, Throwable cause) {}

    private static final String COPIES = ".loaded";

    private final Path directory;
    private final PluginServices services;
    private final Map<String, Loaded> loaded = new LinkedHashMap<>();
    private final List<Failure> failures = new ArrayList<>();
    private int copies;
    private boolean started;

    public PluginManager(Path directory, PluginServices services) {
        this.directory = directory;
        this.services = services;
    }

    public List<Loaded> loaded() {
        return List.copyOf(loaded.values());
    }

    /** Failures since the last call of {@link #loadNew()}. */
    public List<Failure> failures() {
        return List.copyOf(failures);
    }

    /** Loads every jar in the plugins folder that is not loaded yet, in dependency order. */
    public synchronized List<Loaded> loadNew() throws IOException {
        failures.clear();
        Files.createDirectories(directory);
        if (!started) {
            started = true;
            clearCopies();
        }
        Map<String, Path> jars = new LinkedHashMap<>();
        Map<String, PluginInfo> waiting = new LinkedHashMap<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path jar : files.filter(file -> file.toString().endsWith(".jar")).sorted().toList()) {
                try {
                    PluginInfo info = PluginDescriptor.read(jar);
                    if (loaded.containsKey(info.name())) {
                        continue;
                    }
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

        // Each round takes every plugin whose dependencies are already up.
        List<Loaded> added = new ArrayList<>();
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
                    Loaded plugin = enable(info, jars.get(info.name()));
                    if (plugin != null) {
                        added.add(plugin);
                    }
                    progress = true;
                }
            }
        }
        waiting.values().forEach(info -> fail(info.name(), info.version(), "dependency cycle with "
                + String.join(", ", info.depends()), null));
        return added;
    }


    /** The plugin that was loaded from this jar, if any. */
    public synchronized String nameOf(Path jar) {
        return loaded.values().stream().filter(plugin -> plugin.source().equals(jar)).map(plugin -> plugin.info().name())
                .findFirst().orElse(null);
    }

    /** True when the jar on disk is byte for byte what is loaded, so a swap would change nothing. */
    public synchronized boolean isCurrent(Path jar) {
        return loaded.values().stream().filter(plugin -> plugin.source().equals(jar)).anyMatch(plugin -> {
            try {
                return Files.mismatch(jar, plugin.copy()) == -1;
            } catch (IOException unreadable) {
                return false;
            }
        });
    }
    /** Names of loaded plugins that need the given one and therefore block unloading it. */
    public synchronized List<String> dependents(String name) {
        return loaded.values().stream().filter(plugin -> plugin.info().depends().contains(name))
                .map(plugin -> plugin.info().name()).toList();
    }

    /** Returns false when no such plugin is loaded. Throws when other plugins still depend on it. */
    public synchronized boolean unload(String name) {
        Loaded plugin = loaded.get(name);
        if (plugin == null) {
            return false;
        }
        List<String> dependents = dependents(name);
        if (!dependents.isEmpty()) {
            throw new IllegalStateException(String.join(", ", dependents) + (dependents.size() == 1 ? " still needs it" : " still need it"));
        }
        loaded.remove(name);
        release(plugin);
        return true;
    }

    public synchronized void disableAll() {
        List<Loaded> reversed = new ArrayList<>(loaded.values());
        Collections.reverse(reversed);
        loaded.clear();
        reversed.forEach(this::release);
    }

    private Loaded enable(PluginInfo info, Path jar) {
        Path copy = null;
        PluginClassLoader loader = null;
        PluginContext context = null;
        try {
            // The jar is loaded from a copy, so the original can be replaced while the server runs.
            // The process id keeps two servers that share a folder, or a crashed one, from clashing over a name.
            copy = Files.createDirectories(directory.resolve(COPIES))
                    .resolve(info.name() + "-" + ProcessHandle.current().pid() + "-" + copies++ + ".jar");
            Files.copy(jar, copy, StandardCopyOption.REPLACE_EXISTING);
            List<PluginClassLoader> dependencies = info.depends().stream().map(name -> loaded.get(name).loader()).toList();
            loader = new PluginClassLoader(copy.toUri().toURL(), PluginManager.class.getClassLoader(), dependencies);
            Class<?> main = Class.forName(info.main(), true, loader);
            if (!Plugin.class.isAssignableFrom(main)) {
                throw new IllegalArgumentException(info.main() + " does not implement Plugin");
            }
            Plugin plugin = (Plugin) main.getDeclaredConstructor().newInstance();
            context = new PluginContext(info, loader, directory.resolve(info.name()), services);
            plugin.enable(context);
            Loaded result = new Loaded(info, plugin, loader, context, jar, copy);
            loaded.put(info.name(), result);
            return result;
        } catch (ClassNotFoundException e) {
            fail(info.name(), info.version(), "main class " + info.main() + " not found", null);
        } catch (NoSuchMethodException e) {
            fail(info.name(), info.version(), info.main() + " needs a public constructor without arguments", null);
        } catch (IllegalArgumentException e) {
            fail(info.name(), info.version(), e.getMessage(), null);
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError e) {
            Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            fail(info.name(), info.version(), "error while enabling: " + cause, cause);
        }
        // A plugin that failed halfway must not leave listeners or an open jar behind.
        if (context != null) {
            context.close();
        }
        closeQuietly(loader, copy);
        return null;
    }

    private void release(Loaded plugin) {
        try {
            plugin.plugin().disable();
        } catch (RuntimeException | LinkageError e) {
            fail(plugin.info().name(), plugin.info().version(), "error while disabling: " + e, e);
        }
        plugin.context().close();
        closeQuietly(plugin.loader(), plugin.copy());
    }

    private static void closeQuietly(PluginClassLoader loader, Path copy) {
        try {
            if (loader != null) {
                loader.close();
            }
            if (copy != null) {
                Files.deleteIfExists(copy);
            }
        } catch (IOException leftover) {
            // the copy is removed on the next start
        }
    }

    /** Copies left behind by a crash; one that cannot be deleted is simply left alone. */
    private void clearCopies() throws IOException {
        Path folder = directory.resolve(COPIES);
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> files = Files.list(folder)) {
            for (Path file : files.toList()) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException inUse) {
                    // still locked by something else
                }
            }
        }
    }

    private void fail(String name, String version, String reason, Throwable cause) {
        failures.add(new Failure(name, version, reason, cause));
    }
}
