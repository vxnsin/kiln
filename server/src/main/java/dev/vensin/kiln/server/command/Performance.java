package dev.vensin.kiln.server.command;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.Locale;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.server.ServerTickMonitorEvent;
import net.minestom.server.instance.Instance;

/** Keeps the last ten seconds of tick times, which is enough to tell a smooth server from a struggling one. */
public final class Performance {

    private static final int WINDOW = 200;
    private static final double TICK_BUDGET_MILLIS = 50;
    private static final long CPU_SAMPLE_MILLIS = 2000;

    private final double[] tickMillis = new double[WINDOW];
    private final long[] tickNanos = new long[WINDOW];
    private final long startedAt = System.nanoTime();
    private int ticks;
    private volatile double cpuLoad = -1;

    public void register(GlobalEventHandler events) {
        events.addListener(ServerTickMonitorEvent.class, event -> record(event.getTickMonitor().getTickTime()));
        Thread.ofPlatform().daemon().name("cpu-sampler").start(this::sampleCpu);
    }

    private synchronized void record(double millis) {
        int slot = ticks % WINDOW;
        tickMillis[slot] = millis;
        tickNanos[slot] = System.nanoTime();
        ticks++;
    }

    public void report(CommandSender sender) {
        Chat.say(sender, "<white>Performance</white>");
        Chat.item(sender, row("Ticks", ticksLine()));
        Runtime runtime = Runtime.getRuntime();
        long megabyte = 1024 * 1024;
        Chat.item(sender, row("Memory", "<white>" + (runtime.totalMemory() - runtime.freeMemory()) / megabyte
                + " MB</white> of " + runtime.maxMemory() / megabyte + " MB"));
        Chat.item(sender, row("CPU", cpuLine(runtime)));
        Chat.item(sender, row("World", worldLine()));
        Chat.item(sender, row("Players", "<white>"
                + MinecraftServer.getConnectionManager().getOnlinePlayers().size() + "</white> online"));
        Chat.item(sender, row("Uptime", "<white>" + uptime() + "</white>"));
    }

    private synchronized String ticksLine() {
        int count = Math.min(ticks, WINDOW);
        if (count < 2) {
            return "no ticks measured yet";
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
        double average = sum / count;
        double perSecond = Math.min(1000 / TICK_BUDGET_MILLIS, (count - 1) / ((newest - oldest) / 1e9));
        String color = average < TICK_BUDGET_MILLIS * 0.5 ? "green" : average < TICK_BUDGET_MILLIS ? "yellow" : "red";
        return String.format(Locale.ROOT, "<white>%.1f</white> per second · <%s>%.1f ms</%s> average · %.1f ms peak "
                + "· %.0f %% of the tick used", perSecond, color, average, color, peak, average / TICK_BUDGET_MILLIS * 100);
    }

    private String cpuLine(Runtime runtime) {
        double load = cpuLoad;
        String share = load < 0 ? "" : String.format(Locale.ROOT, "<white>%.0f %%</white> of %d cores · ", load * 100,
                runtime.availableProcessors());
        return share + ManagementFactory.getThreadMXBean().getThreadCount() + " threads";
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

    private static String worldLine() {
        int chunks = 0;
        int entities = 0;
        var instances = MinecraftServer.getInstanceManager().getInstances();
        for (Instance instance : instances) {
            chunks += instance.getChunks().size();
            entities += instance.getEntities().size();
        }
        return count(instances.size(), "instance") + " · " + count(chunks, "chunk") + " · " + count(entities, "entity");
    }

    private static String count(int amount, String noun) {
        String plural = noun.endsWith("y") ? noun.substring(0, noun.length() - 1) + "ies" : noun + "s";
        return "<white>" + amount + "</white> " + (amount == 1 ? noun : plural);
    }

    private String uptime() {
        Duration up = Duration.ofNanos(System.nanoTime() - startedAt);
        if (up.toHours() > 0) {
            return up.toHours() + " h " + up.toMinutesPart() + " min";
        }
        return up.toMinutes() > 0 ? up.toMinutes() + " min" : up.toSeconds() + " s";
    }

    private static String row(String label, String text) {
        return "<gold>" + String.format("%-8s", label) + "</gold> <gray>" + text + "</gray>";
    }
}
