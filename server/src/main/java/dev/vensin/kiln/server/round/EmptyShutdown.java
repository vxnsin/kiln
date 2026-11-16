package dev.vensin.kiln.server.round;

import java.time.Duration;
import net.minestom.server.MinecraftServer;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.timer.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Ends a one-round server once everyone has left. A server nobody ever joined keeps waiting for its first player. */
public final class EmptyShutdown {

    private static final Logger LOG = LoggerFactory.getLogger(EmptyShutdown.class);

    private final Duration grace;
    private Task pending;

    public EmptyShutdown(Duration grace) {
        this.grace = grace;
    }

    public void register(GlobalEventHandler events) {
        events.addListener(PlayerSpawnEvent.class, event -> cancel());
        events.addListener(PlayerDisconnectEvent.class, event -> {
            // The leaving player still counts as online while this event runs.
            if (MinecraftServer.getConnectionManager().getOnlinePlayers().size() <= 1) {
                schedule();
            }
        });
    }

    private synchronized void schedule() {
        cancel();
        LOG.info("Empty now, shutting down in {} s unless someone joins", grace.toSeconds());
        pending = MinecraftServer.getSchedulerManager().buildTask(() -> {
            if (MinecraftServer.getConnectionManager().getOnlinePlayers().isEmpty()) {
                LOG.info("Still empty, shutting down");
                MinecraftServer.stopCleanly();
                System.exit(0);
            }
        }).delay(grace).schedule();
    }

    private synchronized void cancel() {
        if (pending != null) {
            pending.cancel();
            pending = null;
        }
    }
}
