package org.craftrank.ashlar;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * One-command tuned settings for common server types. Writes paper/spigot/bukkit configs and
 * server.properties (after backing them up); the server needs a restart to use them.
 */
final class Presets {

    static final List<String> NAMES = List.of("survival", "smp", "skyblock", "minigames");

    private Presets() {
    }

    /** @return lines describing what changed */
    static List<String> apply(File root, String name) throws IOException {
        name = name.toLowerCase(Locale.ROOT);
        if (!NAMES.contains(name)) throw new IllegalArgumentException("Unknown preset: " + name);
        int view, sim, monsters, animals, water, ambient;
        boolean nerfSpawners;
        switch (name) {
            case "smp" -> { view = 8; sim = 5; monsters = 40; animals = 8; water = 3; ambient = 1; nerfSpawners = false; }
            case "skyblock" -> { view = 6; sim = 4; monsters = 30; animals = 6; water = 2; ambient = 0; nerfSpawners = true; }
            case "minigames" -> { view = 6; sim = 4; monsters = 10; animals = 2; water = 0; ambient = 0; nerfSpawners = true; }
            default -> { view = 10; sim = 6; monsters = 50; animals = 10; water = 3; ambient = 1; nerfSpawners = false; }
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File backup = new File(root, "ashlar-backup/" + stamp);

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("view-distance", view);
        props.put("simulation-distance", sim);
        props.put("network-compression-threshold", 256);
        props.put("sync-chunk-writes", false);

        Map<String, Object> bukkit = new LinkedHashMap<>();
        bukkit.put("spawn-limits.monsters", monsters);
        bukkit.put("spawn-limits.animals", animals);
        bukkit.put("spawn-limits.water-animals", water);
        bukkit.put("spawn-limits.water-ambient", Math.min(water * 3, 10));
        bukkit.put("spawn-limits.water-underground-creature", Math.min(water, 3));
        bukkit.put("spawn-limits.axolotls", Math.min(water, 3));
        bukkit.put("spawn-limits.ambient", ambient);
        bukkit.put("ticks-per.monster-spawns", 2);
        bukkit.put("ticks-per.animal-spawns", 400);
        bukkit.put("chunk-gc.period-in-ticks", 400);

        String w = "world-settings.default.";
        Map<String, Object> spigot = new LinkedHashMap<>();
        spigot.put(w + "entity-activation-range.animals", 16);
        spigot.put(w + "entity-activation-range.monsters", 24);
        spigot.put(w + "entity-activation-range.raiders", 48);
        spigot.put(w + "entity-activation-range.misc", 8);
        spigot.put(w + "entity-activation-range.water", 8);
        spigot.put(w + "entity-activation-range.villagers", 16);
        spigot.put(w + "entity-activation-range.flying-monsters", 48);
        spigot.put(w + "entity-activation-range.tick-inactive-villagers", false);
        spigot.put(w + "merge-radius.item", 3.5);
        spigot.put(w + "merge-radius.exp", 4.0);
        spigot.put(w + "nerf-spawner-mobs", nerfSpawners);
        spigot.put(w + "mob-spawn-range", Math.min(sim, 6) - 1);

        Map<String, Object> paper = new LinkedHashMap<>();
        paper.put("chunks.prevent-moving-into-unloaded-chunks", true);
        paper.put("chunks.entity-per-chunk-save-limit.experience_orb", 16);
        paper.put("chunks.entity-per-chunk-save-limit.arrow", 16);
        paper.put("chunks.entity-per-chunk-save-limit.snowball", 8);
        paper.put("chunks.entity-per-chunk-save-limit.ender_pearl", 8);
        paper.put("chunks.entity-per-chunk-save-limit.fireball", 8);
        paper.put("chunks.entity-per-chunk-save-limit.small_fireball", 8);
        paper.put("collisions.max-entity-collisions", 2);
        paper.put("environment.optimize-explosions", true);
        paper.put("entities.armor-stands.tick", false);
        paper.put("entities.armor-stands.do-collision-entity-lookups", false);
        paper.put("misc.redstone-implementation", "ALTERNATE_CURRENT");
        paper.put("tick-rates.mob-spawner", name.equals("skyblock") ? 3 : 2);
        paper.put("tick-rates.grass-spread", 4);
        paper.put("tick-rates.container-update", 1);
        paper.put("hopper.disable-move-event", false);
        paper.put("entities.spawning.despawn-ranges.monster.hard", 72);
        paper.put("entities.spawning.despawn-ranges.monster.soft", 30);

        List<String> done = new java.util.ArrayList<>();
        done.add(props(root, "server.properties", props, backup));
        done.add(yaml(root, "bukkit.yml", bukkit, backup));
        done.add(yaml(root, "spigot.yml", spigot, backup));
        done.add(yaml(root, "config/paper-world-defaults.yml", paper, backup));
        done.add("Backup of the old files: ashlar-backup/" + stamp);
        return done;
    }

    private static void backup(File f, File dir, String rel) throws IOException {
        if (!f.isFile()) return;
        File to = new File(dir, rel);
        to.getParentFile().mkdirs();
        Files.copy(f.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String yaml(File root, String rel, Map<String, Object> values, File backup) throws IOException {
        File f = new File(root, rel);
        backup(f, backup, rel);
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        values.forEach(y::set);
        f.getParentFile().mkdirs();
        y.save(f);
        return rel + ": " + values.size() + " settings";
    }

    private static String props(File root, String rel, Map<String, Object> values, File backup) throws IOException {
        File f = new File(root, rel);
        backup(f, backup, rel);
        Properties p = new Properties();
        if (f.isFile()) {
            try (Reader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        values.forEach((k, v) -> p.setProperty(k, String.valueOf(v)));
        try (Writer wr = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
            p.store(wr, "Minecraft server properties (tuned by Ashlar)");
        }
        return rel + ": " + values.size() + " settings";
    }
}
