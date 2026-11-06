package org.craftrank.ashlar;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Zips every world every few hours into backups/, keeps the newest few. The worlds are saved and
 * auto-save is paused while the files are read, so the copy is consistent; zipping runs off the main thread.
 */
final class Backup {

    private final AshlarConfig cfg;
    private final Plugin plugin;
    private final AtomicBoolean running = new AtomicBoolean();
    private BukkitTask task;
    volatile String last = "none yet";

    Backup(AshlarConfig cfg, Plugin plugin) {
        this.cfg = cfg;
        this.plugin = plugin;
    }

    void start() {
        stop();
        if (!this.cfg.backups) {
            this.last = "off";
            return;
        }
        long every = this.cfg.backupHours * 72_000L;
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, () -> run(null), every, every);
        List<File> l = list();
        if (!l.isEmpty()) this.last = l.get(0).getName();
    }

    void stop() {
        if (this.task != null) this.task.cancel();
        this.task = null;
    }

    File dir() {
        return new File(this.cfg.backupFolder);
    }

    List<File> list() {
        File[] fs = dir().listFiles((d, n) -> n.startsWith("ashlar-") && n.endsWith(".zip"));
        if (fs == null) return List.of();
        List<File> l = new ArrayList<>(Arrays.asList(fs));
        l.sort(Comparator.comparing(File::getName).reversed());
        return l;
    }

    /** Must be called on the main thread. done (optional) gets a short result line, also on the main thread. */
    boolean run(Consumer<String> done) {
        if (!this.running.compareAndSet(false, true)) {
            if (done != null) done.accept("A backup is already running.");
            return false;
        }
        File out = dir();
        out.mkdirs();
        long need = 0;
        List<World> worlds = Bukkit.getWorlds();
        for (World w : worlds) need += size(w.getWorldFolder().toPath());
        if (out.getUsableSpace() < need + (512L << 20)) {
            this.running.set(false);
            String m = "Backup skipped: not enough free disk space.";
            Ashlar.LOG.warn(m);
            if (done != null) done.accept(m);
            return false;
        }
        List<Path> folders = new ArrayList<>();
        for (World w : worlds) {
            w.save();
            w.setAutoSave(false);
            folders.add(w.getWorldFolder().toPath());
        }
        String name = "ashlar-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss", Locale.ROOT)) + ".zip";
        File zip = new File(out, name);
        File tmp = new File(out, name + ".part");
        long t0 = System.currentTimeMillis();
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            String result;
            try (OutputStream os = Files.newOutputStream(tmp.toPath()); ZipOutputStream z = new ZipOutputStream(os)) {
                for (Path f : folders) add(z, f, f.getParent());
                z.finish();
                os.close();
                Files.move(tmp.toPath(), zip.toPath());
                prune();
                this.last = name;
                result = String.format(Locale.ROOT, "Backup saved: %s/%s (%d MB, %.1fs)", this.cfg.backupFolder, name,
                    zip.length() >> 20, (System.currentTimeMillis() - t0) / 1000.0);
                Ashlar.LOG.info(result);
            } catch (IOException e) {
                tmp.delete();
                result = "Backup failed: " + e.getMessage();
                Ashlar.LOG.error(result);
            }
            String r = result;
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                for (World w : Bukkit.getWorlds()) w.setAutoSave(true);
                this.running.set(false);
                if (done != null) done.accept(r);
            });
        });
        return true;
    }

    private void prune() {
        List<File> l = list();
        for (int i = this.cfg.backupKeep; i < l.size(); i++) l.get(i).delete();
    }

    private static void add(ZipOutputStream z, Path folder, Path base) throws IOException {
        Files.walkFileTree(folder, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                String n = file.getFileName().toString();
                if (n.equals("session.lock")) return FileVisitResult.CONTINUE;
                z.putNextEntry(new ZipEntry(base.relativize(file).toString().replace('\\', '/')));
                try {
                    Files.copy(file, z);
                } catch (IOException e) {
                    // a file that disappeared mid-backup (e.g. a temp file) is skipped
                }
                z.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static long size(Path p) {
        try (var s = Files.walk(p)) {
            return s.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
        } catch (IOException e) {
            return 0;
        }
    }
}
