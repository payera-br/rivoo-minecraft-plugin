package dev.rivoo.minecraft.paper;

import dev.rivoo.minecraft.core.platform.Scheduler;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;

public final class PaperScheduler implements Scheduler {
    private final Plugin plugin;

    public PaperScheduler(Plugin plugin) { this.plugin = plugin; }

    @Override public void sync(Runnable task) {
        if (Bukkit.isPrimaryThread()) task.run();
        else Bukkit.getScheduler().runTask(plugin, task);
    }

    @Override public void async(Runnable task) { Bukkit.getScheduler().runTaskAsynchronously(plugin, task); }

    @Override public Task repeatSync(Runnable task, Duration every) {
        BukkitTask t = Bukkit.getScheduler().runTaskTimer(plugin, task, ticks(every), ticks(every));
        return t::cancel;
    }

    @Override public Task repeatAsync(Runnable task, Duration every) {
        BukkitTask t = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, ticks(every), ticks(every));
        return t::cancel;
    }

    private static long ticks(Duration d) { return Math.max(1, d.toMillis() / 50); }
}
