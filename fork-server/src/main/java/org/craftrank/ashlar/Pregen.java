package org.craftrank.ashlar;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Generates the chunks around spawn while nobody is online, so players never wait for new terrain.
 * It works outward in a square spiral, a few chunks at a time, stops the moment someone joins or the
 * server gets busy, and remembers where it was in ashlar-pregen.yml.
 */
final class Pregen {

    private final AshlarConfig cfg;
    private final File stateFile = new File("ashlar-pregen.yml");
    private final YamlConfiguration state = YamlConfiguration.loadConfiguration(this.stateFile);
    private final AtomicInteger inFlight = new AtomicInteger();
    private BukkitTask task;
    private boolean paused;
    private int sinceSave;
    volatile String last = "off";

    Pregen(AshlarConfig cfg) {
        this.cfg = cfg;
    }

    void start(Plugin plugin) {
        stop();
        if (!this.cfg.pregen) {
            this.last = "off";
            return;
        }
        this.last = "waiting for an empty server";
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 200L, 10L);
    }

    void stop() {
        if (this.task != null) this.task.cancel();
        this.task = null;
        save();
    }

    void setPaused(boolean p) {
        this.paused = p;
    }

    void reset() {
        for (String k : this.state.getKeys(false)) this.state.set(k, null);
        save();
    }

    private int total() {
        int r = (this.cfg.pregenRadius >> 4) + 1;
        return (2 * r + 1) * (2 * r + 1);
    }

    private void tick() {
        if (this.paused) {
            this.last = "paused";
            return;
        }
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            this.last = "waiting (players online)";
            return;
        }
        if (Bukkit.getAverageTickTime() > 35) {
            this.last = "waiting (server busy)";
            return;
        }
        int total = total();
        for (String name : this.cfg.pregenWorlds) {
            World w = Bukkit.getWorld(name);
            if (w == null) continue;
            int done = this.state.getInt(name, 0);
            if (done >= total) continue;
            Location spawn = w.getSpawnLocation();
            int cx = spawn.getBlockX() >> 4, cz = spawn.getBlockZ() >> 4;
            while (this.inFlight.get() < this.cfg.pregenParallel && done < total) {
                int[] d = spiral(done++);
                this.inFlight.incrementAndGet();
                w.getChunkAtAsync(cx + d[0], cz + d[1], true).whenComplete((c, err) -> this.inFlight.decrementAndGet());
            }
            this.state.set(name, done);
            if (++this.sinceSave >= 20) save();
            this.last = String.format(java.util.Locale.ROOT, "%s %,d / %,d chunks (%d%%)", name, done, total, done * 100L / total);
            return;
        }
        this.last = "done (" + total + " chunks per world, radius " + this.cfg.pregenRadius + " blocks)";
    }

    private void save() {
        this.sinceSave = 0;
        try {
            this.state.save(this.stateFile);
        } catch (IOException ignored) {
        }
    }

    /** Offset of the n-th chunk in a square spiral that starts at 0,0. */
    static int[] spiral(int n) {
        if (n == 0) return new int[] {0, 0};
        int k = (int) Math.ceil((Math.sqrt(n + 1) - 1) / 2);
        int t = 2 * k + 1;
        int m = t * t;
        t -= 1;
        if (n >= m - t) return new int[] {k - (m - n), -k};
        m -= t;
        if (n >= m - t) return new int[] {-k, -k + (m - n)};
        m -= t;
        if (n >= m - t) return new int[] {-k + (m - n), k};
        return new int[] {k, k - (m - n - t)};
    }
}
