package com.mcaia.plugin.util;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;

/** Shared Paper/Folia scheduling helpers. */
public final class FoliaTasks {

    private FoliaTasks() {}

    public static void async(JavaPlugin plugin, Runnable task) {
        plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }

    public static ScheduledTask asyncDelayed(JavaPlugin plugin, Runnable task, long delay, TimeUnit unit) {
        return plugin.getServer().getAsyncScheduler()
                .runDelayed(plugin, ignored -> task.run(), Math.max(0, delay), unit);
    }

    public static ScheduledTask asyncRepeating(JavaPlugin plugin, Runnable task,
                                                long initialDelay, long period, TimeUnit unit) {
        return plugin.getServer().getAsyncScheduler().runAtFixedRate(
                plugin, ignored -> task.run(), Math.max(0, initialDelay), Math.max(1, period), unit);
    }

    public static void global(JavaPlugin plugin, Runnable task) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
    }

    public static ScheduledTask globalDelayed(JavaPlugin plugin, Runnable task, long delayTicks) {
        return plugin.getServer().getGlobalRegionScheduler()
                .runDelayed(plugin, ignored -> task.run(), Math.max(1, delayTicks));
    }

    public static boolean entity(JavaPlugin plugin, Player player, Runnable task, Runnable retired) {
        boolean scheduled = player.getScheduler().execute(plugin, task, retired, 1L);
        // Folia does not invoke either callback when the entity scheduler is
        // already retired at scheduling time, so complete that path here.
        if (!scheduled && retired != null) retired.run();
        return scheduled;
    }

    public static void forSender(JavaPlugin plugin, CommandSender sender, Runnable task, Runnable retired) {
        if (sender instanceof Player player) {
            entity(plugin, player, task, retired);
        } else {
            global(plugin, task);
        }
    }
}
