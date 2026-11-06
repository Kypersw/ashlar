package org.craftrank.ashlar;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Lowers the simulation distance one step at a time while the server is behind (high MSPT)
 * and raises it again when there is room. Players keep their view distance, only how far
 * mobs, redstone and crops are ticked changes, so lag drops without anyone noticing.
 */
final class DynamicDistance {

    private final AshlarConfig cfg;
    private BukkitTask task;
    private int highStreak, lowStreak;
    volatile String last = "not started";

    DynamicDistance(AshlarConfig cfg) {
        this.cfg = cfg;
    }

    void start(Plugin plugin) {
        stop();
        if (!this.cfg.dynDistance) {
            this.last = "off";
            return;
        }
        long period = this.cfg.dynCheckSeconds * 20L;
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, period, period);
        this.last = "watching";
    }

    void stop() {
        if (this.task != null) this.task.cancel();
        this.task = null;
    }

    private void tick() {
        double mspt = Bukkit.getAverageTickTime();
        // need two checks in a row before changing, so one lag spike does not flap the setting
        if (mspt > this.cfg.dynHighMspt) {
            this.highStreak++;
            this.lowStreak = 0;
        } else if (mspt < this.cfg.dynLowMspt) {
            this.lowStreak++;
            this.highStreak = 0;
        } else {
            this.highStreak = this.lowStreak = 0;
        }
        int step = this.highStreak >= 2 ? -1 : this.lowStreak >= 3 ? 1 : 0;
        if (step == 0) return;
        this.highStreak = this.lowStreak = 0;
        for (World w : Bukkit.getWorlds()) {
            int cur = w.getSimulationDistance();
            int next = Math.max(this.cfg.dynMin, Math.min(this.cfg.dynMax, Math.min(w.getViewDistance(), cur + step)));
            if (next != cur) {
                w.setSimulationDistance(next);
                this.last = String.format("%s: %d -> %d (mspt %.1f)", w.getName(), cur, next, mspt);
                Ashlar.LOG.info("Simulation distance {}", this.last);
            }
        }
    }
}
