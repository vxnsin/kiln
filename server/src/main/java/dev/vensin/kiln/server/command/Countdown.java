package dev.vensin.kiln.server.command;

import java.util.function.Consumer;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;

/** A boss bar everyone sees that runs down to zero and then does something, here: stopping the server. */
public final class Countdown {

    private final BossBar bar = BossBar.bossBar(MiniMessage.miniMessage().deserialize(""), 1f, BossBar.Color.RED,
            BossBar.Overlay.PROGRESS);
    private Task task;
    private int total;
    private int left;

    public void register(GlobalEventHandler events) {
        events.addListener(PlayerSpawnEvent.class, event -> {
            if (running()) {
                event.getPlayer().showBossBar(bar);
            }
        });
    }

    public synchronized boolean running() {
        return task != null;
    }

    public synchronized void start(int seconds, String reason, Consumer<String> whenDone) {
        cancel();
        total = seconds;
        left = seconds;
        update(reason);
        MinecraftServer.getConnectionManager().getOnlinePlayers().forEach(player -> player.showBossBar(bar));
        task = MinecraftServer.getSchedulerManager().buildTask(() -> tick(reason, whenDone))
                .delay(TaskSchedule.seconds(1)).repeat(TaskSchedule.seconds(1)).schedule();
    }

    public synchronized boolean cancel() {
        if (task == null) {
            return false;
        }
        task.cancel();
        task = null;
        for (Player player : MinecraftServer.getConnectionManager().getOnlinePlayers()) {
            player.hideBossBar(bar);
        }
        return true;
    }

    private synchronized void tick(String reason, Consumer<String> whenDone) {
        left--;
        if (left <= 0) {
            cancel();
            whenDone.accept(reason);
            return;
        }
        update(reason);
    }

    private void update(String reason) {
        String why = reason.isBlank() ? "" : " <gray>· " + MiniMessage.miniMessage().escapeTags(reason) + "</gray>";
        bar.name(MiniMessage.miniMessage().deserialize("<white>Server stops in <bold>" + time(left) + "</bold></white>" + why));
        bar.progress(Math.max(0f, Math.min(1f, left / (float) total)));
        bar.color(left <= 10 ? BossBar.Color.RED : BossBar.Color.YELLOW);
    }

    static String time(int seconds) {
        return seconds >= 60 ? seconds / 60 + " min" + (seconds % 60 == 0 ? "" : " " + seconds % 60 + " s") : seconds + " s";
    }
}
