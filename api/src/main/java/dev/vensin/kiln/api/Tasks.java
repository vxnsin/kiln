package dev.vensin.kiln.api;

import java.time.Duration;
import net.minestom.server.timer.Task;

/** Scheduled work that belongs to a plugin and is cancelled when the plugin is unloaded or swapped. */
public interface Tasks {

    Task after(Duration delay, Runnable action);

    Task every(Duration interval, Runnable action);

    /** Runs at the start of the next tick, the safe moment to change the world from another thread. */
    Task nextTick(Runnable action);
}
