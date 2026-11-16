package dev.vensin.kiln.server.plugin;

import java.util.concurrent.atomic.AtomicLong;

/** Time spent in one plugin's listeners and timers, kept per second for the last ten seconds. */
public final class PluginTiming {

    private static final int SECONDS = 10;

    private final AtomicLong current = new AtomicLong();
    private final long[] seconds = new long[SECONDS];
    private int filled;
    private int next;

    void add(long nanos) {
        current.addAndGet(nanos);
    }

    /** Called once a second by the server. */
    public synchronized void roll() {
        seconds[next] = current.getAndSet(0);
        next = (next + 1) % SECONDS;
        filled = Math.min(SECONDS, filled + 1);
    }

    /** Average milliseconds per second of server time; divide by 20 for milliseconds per tick. */
    public synchronized double millisPerSecond() {
        if (filled == 0) {
            return 0;
        }
        long sum = 0;
        for (int i = 0; i < filled; i++) {
            sum += seconds[i];
        }
        return sum / 1e6 / filled;
    }
}
