package dev.vensin.kiln.server.plugin;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.jar.JarFile;

/**
 * Reports a jar once it has stopped changing. Copying a jar fires several events while the file is still
 * half written, so a change only counts after the file has been quiet for a moment and opens as a jar.
 */
public final class PluginWatcher {

    private static final long QUIET_MILLIS = 750;
    private static final long POLL_MILLIS = 250;

    private final Path directory;
    private final Consumer<Path> changed;
    private final Consumer<Path> removed;
    private final Map<Path, Long> pending = new ConcurrentHashMap<>();

    public PluginWatcher(Path directory, Consumer<Path> changed, Consumer<Path> removed) {
        this.directory = directory;
        this.changed = changed;
        this.removed = removed;
    }

    public void start() throws IOException {
        WatchService service = directory.getFileSystem().newWatchService();
        directory.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
        Thread.ofPlatform().daemon().name("plugin-watcher").start(() -> watch(service));
        Thread.ofPlatform().daemon().name("plugin-watcher-settle").start(this::settle);
    }

    private void watch(WatchService service) {
        try {
            while (true) {
                WatchKey key = service.take();
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (!(event.context() instanceof Path name) || !name.toString().endsWith(".jar")) {
                        continue;
                    }
                    Path jar = directory.resolve(name);
                    if (event.kind() == StandardWatchEventKinds.ENTRY_DELETE) {
                        pending.remove(jar);
                        removed.accept(jar);
                    } else {
                        pending.put(jar, System.currentTimeMillis());
                    }
                }
                key.reset();
            }
        } catch (InterruptedException | ClosedWatchServiceException stopped) {
            // the server is going down
        }
    }

    private void settle() {
        while (true) {
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            pending.forEach((jar, lastChange) -> {
                if (now - lastChange >= QUIET_MILLIS && readable(jar) && pending.remove(jar, lastChange)) {
                    changed.accept(jar);
                }
            });
        }
    }

    private static boolean readable(Path jar) {
        if (!Files.isRegularFile(jar)) {
            return false;
        }
        try (JarFile ignored = new JarFile(jar.toFile())) {
            return true;
        } catch (IOException stillBeingWritten) {
            return false;
        }
    }
}
