package dev.vensin.kiln.server.plugin;

import dev.vensin.kiln.api.Tasks;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minestom.server.MinecraftServer;
import net.minestom.server.timer.Task;
import net.minestom.server.timer.TaskSchedule;

final class PluginTasks implements Tasks {

    private final Set<Task> tasks = ConcurrentHashMap.newKeySet();

    @Override
    public Task after(Duration delay, Runnable action) {
        return track(MinecraftServer.getSchedulerManager().buildTask(action).delay(delay).schedule());
    }

    @Override
    public Task every(Duration interval, Runnable action) {
        return track(MinecraftServer.getSchedulerManager().buildTask(action).delay(interval).repeat(interval).schedule());
    }

    @Override
    public Task nextTick(Runnable action) {
        return track(MinecraftServer.getSchedulerManager().buildTask(action).delay(TaskSchedule.nextTick()).schedule());
    }

    void cancelAll() {
        List.copyOf(tasks).forEach(Task::cancel);
        tasks.clear();
    }

    private Task track(Task task) {
        tasks.removeIf(old -> !old.isAlive());
        tasks.add(task);
        return task;
    }
}
