package org.craftrank.ashlar;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.papermc.paper.ServerBuildInfo;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Looks for a newer Ashlar build of the same Minecraft version on GitHub (Kypersw/ashlar releases),
 * downloads it next to the server, checks it against the SHA-256 GitHub gives for the file, and puts it
 * in place when the server stops. Also keeps the bundled Geyser and Floodgate current.
 */
final class Updater {

    private static final String RELEASES = "https://api.github.com/repos/Kypersw/ashlar/releases?per_page=20";
    private static final Pattern TAG = Pattern.compile("^v([0-9][0-9.]*)-([0-9]+)$");

    private final AshlarConfig cfg;
    private final Plugin plugin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    private BukkitTask task;
    private volatile Path staged;
    volatile String last = "not checked yet";
    private boolean hooked;

    Updater(AshlarConfig cfg, Plugin plugin) {
        this.cfg = cfg;
        this.plugin = plugin;
    }

    void start() {
        stop();
        if (!this.cfg.updateCheck) {
            this.last = "off";
            return;
        }
        long every = this.cfg.updateHours * 72_000L;
        this.task = Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, this::check, 20L * 60, every);
    }

    void stop() {
        if (this.task != null) this.task.cancel();
        this.task = null;
    }

    void checkNow() {
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, this::check);
    }

    private synchronized void check() {
        try {
            checkAshlar();
        } catch (Exception e) {
            this.last = "check failed: " + e.getMessage();
        }
        if (this.cfg.bedrock) {
            try {
                Bundled.updateBedrock(this.http);
            } catch (Exception e) {
                Ashlar.LOG.warn("Could not check Geyser/Floodgate for updates: {}", e.getMessage());
            }
        }
    }

    private void checkAshlar() throws Exception {
        ServerBuildInfo info = ServerBuildInfo.buildInfo();
        String mc = info.minecraftVersionId();
        int own = info.buildNumber().orElse(0);
        JsonArray rels = JsonParser.parseString(get(RELEASES)).getAsJsonArray();
        JsonObject best = null;
        int bestN = own;
        for (JsonElement el : rels) {
            JsonObject r = el.getAsJsonObject();
            if (r.get("draft").getAsBoolean() || r.get("prerelease").getAsBoolean()) continue;
            Matcher m = TAG.matcher(r.get("tag_name").getAsString());
            if (!m.matches() || !m.group(1).equals(mc)) continue;
            int n = Integer.parseInt(m.group(2));
            if (n > bestN) {
                bestN = n;
                best = r;
            }
        }
        if (best == null) {
            this.last = own == 0 ? "development build, nothing to compare" : "up to date (build " + own + ")";
            return;
        }
        String name = "ashlar-" + mc + ".jar";
        JsonObject asset = null;
        for (JsonElement el : best.getAsJsonArray("assets")) {
            if (el.getAsJsonObject().get("name").getAsString().equals(name)) asset = el.getAsJsonObject();
        }
        if (asset == null) {
            this.last = "build " + bestN + " has no " + name;
            return;
        }
        if (own == 0) {
            this.last = "build " + bestN + " is available (this is a development build, so it is not installed)";
            return;
        }
        if (!this.cfg.updateDownload) {
            this.last = "build " + bestN + " is available: " + best.get("html_url").getAsString();
            Ashlar.LOG.info("A newer Ashlar is available: {}", this.last);
            return;
        }
        String digest = asset.has("digest") && !asset.get("digest").isJsonNull() ? asset.get("digest").getAsString() : "";
        if (!digest.startsWith("sha256:")) {
            this.last = "build " + bestN + " has no checksum, not downloaded";
            return;
        }
        String url = asset.get("browser_download_url").getAsString();
        if (!url.startsWith("https://github.com/Kypersw/ashlar/releases/download/")) throw new IllegalStateException("unexpected download address");
        Path dir = Path.of("ashlar-update");
        Files.createDirectories(dir);
        Path part = dir.resolve(name + ".part"), done = dir.resolve(name);
        download(url, part, digest.substring(7));
        Files.move(part, done, StandardCopyOption.REPLACE_EXISTING);
        this.staged = done;
        this.last = "build " + bestN + " downloaded, it replaces this one when the server stops";
        Ashlar.LOG.info("Ashlar build {} downloaded and checked; it is used after the next restart.", bestN);
        if (!this.hooked) {
            this.hooked = true;
            Runtime.getRuntime().addShutdownHook(new Thread(this::install, "Ashlar update"));
        }
    }

    /** Runs when the JVM exits: swaps the server jar for the downloaded one. */
    private void install() {
        Path s = this.staged;
        if (s == null || !Files.isRegularFile(s)) return;
        String cp = System.getProperty("java.class.path", "");
        String first = cp.split(File.pathSeparator)[0];
        Path jar = Path.of(first).toAbsolutePath();
        if (!first.endsWith(".jar") || !Files.isRegularFile(jar)) {
            System.out.println("[Ashlar] New version is in " + s.toAbsolutePath() + "; replace your server jar with it.");
            return;
        }
        try {
            Files.move(s, jar, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[Ashlar] Updated " + jar.getFileName() + ". The new build starts next time.");
        } catch (Exception e) {
            // Windows keeps the running jar locked
            System.out.println("[Ashlar] Could not replace " + jar.getFileName() + " (" + e.getMessage() + "). Copy " + s.toAbsolutePath() + " over it.");
        }
    }

    private String get(String url) throws Exception {
        HttpResponse<String> r = this.http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
            .header("Accept", "application/vnd.github+json").header("User-Agent", "Ashlar").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
        return r.body();
    }

    void download(String url, Path to, String sha256) throws Exception {
        download(this.http, url, to, sha256);
    }

    static void download(HttpClient http, String url, Path to, String sha256) throws Exception {
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
            .header("User-Agent", "Ashlar").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = r.body(); var out = Files.newOutputStream(to)) {
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
                out.write(buf, 0, n);
            }
        }
        String got = HexFormat.of().formatHex(md.digest());
        if (!got.equalsIgnoreCase(sha256)) {
            Files.deleteIfExists(to);
            throw new IllegalStateException("checksum does not match");
        }
    }
}
