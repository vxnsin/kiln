package dev.vensin.kiln.server.command;

import com.sun.management.OperatingSystemMXBean;
import dev.vensin.kiln.server.plugin.PluginTiming;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import net.minestom.server.instance.Instance;

/** Keeps the last ten seconds of tick times, which is enough to tell a smooth server from a struggling one. */
public final class Performance {

    /** Everything the report shows, in one value, for the chat report and the web dashboard alike. */
    public record Snapshot(double ticksPerSecond, double averageMillis, double peakMillis, long usedMegabytes,
                           long maxMegabytes, double cpuShare, int cores, int threads, int instances, int chunks,
                           int entities, int players, long uptimeSeconds, List<PluginShare> plugins) {}

    public record PluginShare(String name, double millisPerTick) {}

    private static final int WINDOW = 200;
    private static final double TICK_BUDGET_MILLIS = 50;
    private static final long CPU_SAMPLE_MILLIS = 2000;
    private static final double TICKS_PER_SECOND = 20;
    private static final PluginTiming NOTHING = new PluginTiming();

    private final double[] tickMillis = new double[WINDOW];
    private final long[] tickNanos = new long[WINDOW];
    private final long startedAt = System.nanoTime();
    private int ticks;
    private volatile double cpuLoad = -1;
    private Map<String, PluginTiming> timings = Map.of();
    private Supplier<List<String>> loadedPlugins = List::of;

    public void register(GlobalEventHandler events) {
        events.addListener(ServerTickMonitorEvent.class, event -> record(event.getTickMonitor().getTickTime()));
        Thread.ofPlatform().daemon().name("cpu-sampler").start(this::sampleCpu);
    }

    /** Starts measuring plugins; their share shows up as its own block in the report. */
    public void plugins(Map<String, PluginTiming> timings, Supplier<List<String>> loaded) {
        this.timings = timings;
        this.loadedPlugins = loaded;
        MinecraftServer.getSchedulerManager().buildTask(() -> timings.values().forEach(PluginTiming::roll))
                .delay(Duration.ofSeconds(1)).repeat(Duration.ofSeconds(1)).schedule();
    }

    public Snapshot snapshot() {
        double[] ticksNow = tickStats();
        Runtime runtime = Runtime.getRuntime();
        long megabyte = 1024 * 1024;
        int chunks = 0;
        int entities = 0;
        var instances = MinecraftServer.getInstanceManager().getInstances();
        for (Instance instance : instances) {
            chunks += instance.getChunks().size();
            entities += instance.getEntities().size();
        }
        List<PluginShare> plugins = loadedPlugins.get().stream()
                .map(name -> new PluginShare(name, timings.getOrDefault(name, NOTHING).millisPerSecond() / TICKS_PER_SECOND))
                .sorted((a, b) -> Double.compare(b.millisPerTick(), a.millisPerTick()))
                .toList();
        return new Snapshot(ticksNow[0], ticksNow[1], ticksNow[2], (runtime.totalMemory() - runtime.freeMemory()) / megabyte,
                runtime.maxMemory() / megabyte, cpuLoad, runtime.availableProcessors(),
                ManagementFactory.getThreadMXBean().getThreadCount(), instances.size(), chunks, entities,
                MinecraftServer.getConnectionManager().getOnlinePlayers().size(),
                Duration.ofNanos(System.nanoTime() - startedAt).toSeconds(), plugins);
    }

    public void report(CommandSender sender) {
        Snapshot now = snapshot();
        Chat.say(sender, "<white>Performance</white>");
        if (now.ticksPerSecond() < 0) {
            Chat.item(sender, row("Ticks", "no ticks measured yet"));
        } else {
            String color = color(now.averageMillis() / TICK_BUDGET_MILLIS, 0.5, 1);
            Chat.item(sender, row("Ticks", String.format(Locale.ROOT, "<white>%.1f</white> per second · <%s>%.1f ms</%s> "
                    + "average · %.1f ms peak · %.0f %% of the tick used", now.ticksPerSecond(), color, now.averageMillis(),
                    color, now.peakMillis(), now.averageMillis() / TICK_BUDGET_MILLIS * 100)));
        }
        Chat.item(sender, row("Memory", "<white>" + now.usedMegabytes() + " MB</white> of " + now.maxMegabytes() + " MB"));
        String cpu = now.cpuShare() < 0 ? "" : String.format(Locale.ROOT, "<white>%.0f %%</white> of %d cores · ",
                now.cpuShare() * 100, now.cores());
        Chat.item(sender, row("CPU", cpu + now.threads() + " threads"));
        Chat.item(sender, row("World", count(now.instances(), "instance") + " · " + count(now.chunks(), "chunk") + " · "
                + count(now.entities(), "entity")));
        Chat.item(sender, row("Players", "<white>" + now.players() + "</white> online"));
        Chat.item(sender, row("Uptime", "<white>" + uptime(now.uptimeSeconds()) + "</white>"));
        if (now.plugins().isEmpty()) {
            return;
        }
        int width = now.plugins().stream().mapToInt(plugin -> plugin.name().length()).max().orElse(0);
        Chat.item(sender, "<gold>Plugins</gold>  <gray>time in their listeners and timers, last 10 s</gray>");
        for (PluginShare plugin : now.plugins()) {
            double share = plugin.millisPerTick() / TICK_BUDGET_MILLIS;
            String color = color(share, 0.1, 0.4);
            Chat.item(sender, String.format(Locale.ROOT, "  <white>%-" + width + "s</white>  <%s>%.3f ms</%s> "
                    + "<gray>per tick · %.1f %%</gray>", plugin.name(), color, plugin.millisPerTick(), color, share * 100));
        }
    }

    private synchronized void record(double millis) {
        int slot = ticks % WINDOW;
        tickMillis[slot] = millis;
        tickNanos[slot] = System.nanoTime();
        ticks++;
    }

    /** Ticks per second, average and peak milliseconds; a negative rate means there is nothing to say yet. */
    private synchronized double[] tickStats() {
        int count = Math.min(ticks, WINDOW);
        if (count < 2) {
            return new double[] {-1, 0, 0};
        }
        double sum = 0;
        double peak = 0;
        long oldest = Long.MAX_VALUE;
        long newest = 0;
        for (int i = 0; i < count; i++) {
            sum += tickMillis[i];
            peak = Math.max(peak, tickMillis[i]);
            oldest = Math.min(oldest, tickNanos[i]);
            newest = Math.max(newest, tickNanos[i]);
        }
        double perSecond = Math.min(1000 / TICK_BUDGET_MILLIS, (count - 1) / ((newest - oldest) / 1e9));
        return new double[] {perSecond, sum / count, peak};
    }

    /** The first CPU reading takes seconds on Windows, so it is sampled off the tick thread and cached. */
    private void sampleCpu() {
        if (!(ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean system)) {
            return;
        }
        while (true) {
            cpuLoad = system.getProcessCpuLoad();
            try {
                Thread.sleep(CPU_SAMPLE_MILLIS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private static String color(double share, double warn, double bad) {
        return share < warn ? "green" : share < bad ? "yellow" : "red";
    }

    private static String count(int amount, String noun) {
        String plural = noun.endsWith("y") ? noun.substring(0, noun.length() - 1) + "ies" : noun + "s";
        return "<white>" + amount + "</white> " + (amount == 1 ? noun : plural);
    }

    static String uptime(long seconds) {
        Duration up = Duration.ofSeconds(seconds);
        if (up.toHours() > 0) {
            return up.toHours() + " h " + up.toMinutesPart() + " min";
        }
        return up.toMinutes() > 0 ? up.toMinutes() + " min" : up.toSeconds() + " s";
    }

    private static String row(String label, String text) {
        return "<gold>" + String.format("%-8s", label) + "</gold> <gray>" + text + "</gray>";
    }
}
