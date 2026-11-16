package dev.vensin.kiln.server.command;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import net.minestom.server.command.CommandSender;

/**
 * Looks at the server threads every few milliseconds and counts where they are. Methods that show up often are
 * where the time goes. A sample is credited to a plugin when any frame on the stack belongs to it.
 */
final class Profiler {

    private static final String TICK_THREADS = "Ms-Tick";
    private static final long INTERVAL_MILLIS = 5;
    private static final int TOP = 8;
    private static final int DEPTH = 64;

    private final Supplier<Map<String, String>> pluginPackages;
    private final AtomicBoolean running = new AtomicBoolean();

    /** {@code pluginPackages} maps a package prefix like "myplugin." to the plugin's name. */
    Profiler(Supplier<Map<String, String>> pluginPackages) {
        this.pluginPackages = pluginPackages;
    }

    void run(CommandSender sender, int seconds) {
        if (!running.compareAndSet(false, true)) {
            Chat.error(sender, "A profile is already running.");
            return;
        }
        Chat.say(sender, "<gray>Profiling the server threads for " + seconds + " s...</gray>");
        Thread.ofPlatform().daemon().name("profiler").start(() -> {
            try {
                report(sender, seconds, sample(seconds));
            } finally {
                running.set(false);
            }
        });
    }

    private record Samples(int total, int busy, Map<String, Integer> methods, Map<String, Integer> plugins) {}

    private Samples sample(int seconds) {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        Map<String, String> packages = pluginPackages.get();
        long[] ids = Thread.getAllStackTraces().keySet().stream()
                .filter(thread -> thread.getName().startsWith(TICK_THREADS)).mapToLong(Thread::threadId).toArray();
        Map<String, Integer> methods = new HashMap<>();
        Map<String, Integer> plugins = new HashMap<>();
        int total = 0;
        int busy = 0;
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (System.nanoTime() < end) {
            for (ThreadInfo info : threads.getThreadInfo(ids, DEPTH)) {
                if (info == null) {
                    continue;
                }
                total++;
                StackTraceElement[] stack = info.getStackTrace();
                if (info.getThreadState() != Thread.State.RUNNABLE || stack.length == 0 || waiting(stack[0])) {
                    continue;
                }
                busy++;
                methods.merge(name(stack[0]), 1, Integer::sum);
                String plugin = owner(stack, packages);
                if (plugin != null) {
                    plugins.merge(plugin, 1, Integer::sum);
                }
            }
            try {
                Thread.sleep(INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                break;
            }
        }
        return new Samples(total, busy, methods, plugins);
    }

    private void report(CommandSender sender, int seconds, Samples samples) {
        if (samples.total() == 0) {
            Chat.error(sender, "No server threads found to profile.");
            return;
        }
        Chat.say(sender, String.format(Locale.ROOT, "<white>Profile of %d s</white> <gray>%d samples, the server threads "
                + "were busy in %.0f %% of them</gray>", seconds, samples.total(), 100.0 * samples.busy() / samples.total()));
        if (samples.busy() == 0) {
            Chat.item(sender, "<gray>Nothing to show: the server was idle the whole time.</gray>");
            return;
        }
        Chat.item(sender, "<gold>Busiest methods</gold>");
        top(samples.methods()).forEach(entry -> Chat.item(sender, String.format(Locale.ROOT,
                "  <white>%5.1f %%</white>  <gray>%s</gray>", 100.0 * entry.getValue() / samples.busy(), entry.getKey())));
        if (!samples.plugins().isEmpty()) {
            Chat.item(sender, "<gold>Plugins on the stack</gold>");
            top(samples.plugins()).forEach(entry -> Chat.item(sender, String.format(Locale.ROOT,
                    "  <white>%5.1f %%</white>  <gray>%s</gray>", 100.0 * entry.getValue() / samples.busy(), entry.getKey())));
        }
    }

    private static List<Map.Entry<String, Integer>> top(Map<String, Integer> counts) {
        return counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(TOP).toList();
    }

    /** Frames where a thread that the JVM calls runnable is really waiting for the next tick or for a socket. */
    private static boolean waiting(StackTraceElement top) {
        String type = top.getClassName();
        return type.startsWith("sun.nio.ch.") || type.startsWith("java.util.concurrent.locks.")
                || type.equals("java.lang.Thread") && top.getMethodName().startsWith("sleep")
                || top.getMethodName().equals("waitUntilNextTick");
    }

    private static String owner(StackTraceElement[] stack, Map<String, String> packages) {
        for (StackTraceElement frame : stack) {
            for (Map.Entry<String, String> entry : packages.entrySet()) {
                if (frame.getClassName().startsWith(entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        return null;
    }

    private static String name(StackTraceElement frame) {
        String type = frame.getClassName();
        return type.substring(type.lastIndexOf('.') + 1) + "." + frame.getMethodName();
    }
}
