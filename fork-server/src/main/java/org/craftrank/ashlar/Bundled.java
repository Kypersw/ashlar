package org.craftrank.ashlar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Puts the plugins Ashlar builds on into the plugins folder before plugins load:
 * - ViaVersion, ViaBackwards and ViaRewind (GPLv3, shipped inside the jar) so players on other game versions can join;
 * - Geyser and Floodgate (MIT, downloaded from GeyserMC with their SHA-256) so Bedrock players can join.
 * Geyser follows Bedrock updates closely, so it is downloaded fresh instead of being frozen inside the jar.
 * A copy the server owner added themselves is never touched.
 */
public final class Bundled {

    private static final String[] VIA = {"ViaVersion-5.12.2.jar", "ViaBackwards-5.12.2.jar", "ViaRewind-4.2.0.jar"};
    private static final String[][] BEDROCK = {{"geyser", "Geyser-Spigot.jar"}, {"floodgate", "Floodgate-Spigot.jar"}};
    private static final String MARKER = ".ashlar-bundled";
    private static Path pluginsDir = Path.of("plugins");

    private Bundled() {
    }

    public static void install(Path plugins) {
        pluginsDir = plugins;
        try {
            File cfgFile = new File("ashlar.yml");
            YamlConfiguration y = YamlConfiguration.loadConfiguration(cfgFile);
            boolean changed = false;
            if (!y.contains("multi-version.enabled")) {
                y.set("multi-version.enabled", true);
                y.setComments("multi-version", List.of(
                    "Lets players on other game versions join (old clients down to 1.8 and newer ones).",
                    "Uses the ViaVersion, ViaBackwards and ViaRewind plugins shipped with Ashlar."));
                changed = true;
            }
            if (!y.contains("bedrock.enabled")) {
                y.set("bedrock.enabled", true);
                y.setComments("bedrock", List.of(
                    "Lets Bedrock players (phones, consoles, Windows) join on UDP port 19132 through Geyser and Floodgate.",
                    "Ashlar downloads both from GeyserMC on the first start and checks their SHA-256."));
                changed = true;
            }
            if (changed) y.save(cfgFile);
            boolean via = y.getBoolean("multi-version.enabled", true);
            boolean bedrock = y.getBoolean("bedrock.enabled", true);

            Files.createDirectories(plugins);
            Path marker = plugins.resolve(MARKER);
            Set<String> ours = new LinkedHashSet<>();
            if (Files.isRegularFile(marker)) ours.addAll(Files.readAllLines(marker, StandardCharsets.UTF_8));
            List<String> present = jars(plugins);
            Set<String> now = new LinkedHashSet<>();

            for (String jar : VIA) {
                String prefix = jar.substring(0, jar.indexOf('-')).toLowerCase(Locale.ROOT);
                for (String name : present) {
                    if (ours.contains(name) && name.toLowerCase(Locale.ROOT).startsWith(prefix) && (!via || !name.equals(jar))) {
                        Files.deleteIfExists(plugins.resolve(name));
                    }
                }
                if (!via || userCopy(present, ours, prefix)) continue;
                Path to = plugins.resolve(jar);
                if (!Files.isRegularFile(to)) {
                    try (InputStream in = Bundled.class.getResourceAsStream("/ashlar/bundled/" + jar)) {
                        if (in == null) continue;
                        Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                now.add(jar);
            }

            HttpClient http = null;
            for (String[] b : BEDROCK) {
                String prefix = b[0], jar = b[1];
                if (!bedrock) {
                    if (ours.contains(jar)) Files.deleteIfExists(plugins.resolve(jar));
                    continue;
                }
                if (userCopy(present, ours, prefix)) continue;
                Path to = plugins.resolve(jar);
                if (!Files.isRegularFile(to)) {
                    try {
                        if (http == null) http = client();
                        fetchGeyser(http, prefix, to);
                        Ashlar.LOG.info("Downloaded {} for Bedrock players", jar);
                    } catch (Exception e) {
                        Ashlar.LOG.warn("Could not download {} ({}); Bedrock players cannot join until it is there.", jar, e.getMessage());
                        continue;
                    }
                }
                now.add(jar);
            }
            Files.write(marker, now, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Ashlar.LOG.warn("Could not set up the bundled plugins: {}", e.getMessage());
        }
    }

    private static List<String> jars(Path plugins) throws IOException {
        List<String> present = new ArrayList<>();
        try (Stream<Path> s = Files.list(plugins)) {
            s.map(p -> p.getFileName().toString()).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".jar")).forEach(present::add);
        }
        return present;
    }

    private static boolean userCopy(List<String> present, Set<String> ours, String prefix) {
        return present.stream().anyMatch(n -> !ours.contains(n) && n.toLowerCase(Locale.ROOT).startsWith(prefix));
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    /** Latest Spigot build of a GeyserMC project, checked against the SHA-256 their API gives. Returns the build number. */
    private static int fetchGeyser(HttpClient http, String project, Path to) throws Exception {
        String api = "https://download.geysermc.org/v2/projects/" + project + "/versions/latest/builds/latest";
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(api)).timeout(Duration.ofSeconds(20))
            .header("User-Agent", "Ashlar").GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode());
        JsonObject b = JsonParser.parseString(r.body()).getAsJsonObject();
        String version = b.get("version").getAsString();
        int build = b.get("build").getAsInt();
        String sha = b.getAsJsonObject("downloads").getAsJsonObject("spigot").get("sha256").getAsString();
        if (!sha.matches("[a-f0-9]{64}") || !version.matches("[0-9A-Za-z.\\-]+")) throw new IllegalStateException("unexpected answer");
        String url = "https://download.geysermc.org/v2/projects/" + project + "/versions/" + version + "/builds/" + build + "/downloads/spigot";
        Path part = to.resolveSibling(to.getFileName() + ".part");
        Updater.download(http, url, part, sha);
        Files.move(part, to, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(to.resolveSibling("." + project + ".build"), String.valueOf(build));
        return build;
    }

    /** Called by the updater: a newer Geyser or Floodgate goes into plugins/update, which Paper applies on the next start. */
    static void updateBedrock(HttpClient http) throws Exception {
        Path marker = pluginsDir.resolve(MARKER);
        if (!Files.isRegularFile(marker)) return;
        List<String> ours = Files.readAllLines(marker, StandardCharsets.UTF_8);
        for (String[] b : BEDROCK) {
            if (!ours.contains(b[1])) continue;
            Path buildFile = pluginsDir.resolve("." + b[0] + ".build");
            int have = Files.isRegularFile(buildFile) ? Integer.parseInt(Files.readString(buildFile).trim()) : 0;
            String api = "https://download.geysermc.org/v2/projects/" + b[0] + "/versions/latest/builds/latest";
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(api)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "Ashlar").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) continue;
            int latest = JsonParser.parseString(r.body()).getAsJsonObject().get("build").getAsInt();
            if (latest <= have) continue;
            Path upd = pluginsDir.resolve("update");
            Files.createDirectories(upd);
            fetchGeyser(http, b[0], upd.resolve(b[1]));
            Files.move(upd.resolve("." + b[0] + ".build"), buildFile, StandardCopyOption.REPLACE_EXISTING);
            Ashlar.LOG.info("{} build {} downloaded; it is used after the next restart.", b[1], latest);
        }
    }
}
