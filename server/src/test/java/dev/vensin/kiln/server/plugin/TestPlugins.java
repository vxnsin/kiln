package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.Kiln;
import dev.vensin.kiln.api.Plugin;
import java.util.concurrent.atomic.AtomicInteger;
import net.minestom.server.command.builder.Command;

/** Plugins for the manager test. They are packed into jars at test time; see PluginManagerTest. */
final class TestPlugins {

    static final AtomicInteger ENABLED = new AtomicInteger();
    static final AtomicInteger DISABLED = new AtomicInteger();

    private TestPlugins() {}

    public static final class Working implements Plugin {
        @Override
        public void enable(Kiln kiln) {
            ENABLED.incrementAndGet();
            kiln.commands(new Command("working-command"));
        }

        @Override
        public void disable() {
            DISABLED.incrementAndGet();
        }
    }

    public static final class Broken implements Plugin {
        @Override
        public void enable(Kiln kiln) {
            throw new IllegalStateException("this build is broken");
        }
    }
}
