package org.craftrank.ashlar;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Puts ViaVersion, ViaBackwards and ViaRewind (GPLv3, shipped inside the Ashlar jar) into the
 * plugins folder before plugins load, so players on older and newer game versions can join.
 * A copy the server owner added themselves is never touched.
 */
public final class Bundled {

    private static final String[] JARS = {"ViaVersion-5.12.2.jar", "ViaBackwards-5.12.2.jar", "ViaRewind-4.2.0.jar"};
    private static final String MARKER = ".ashlar-bundled";

    private Bundled() {
    }

    public static void install(Path plugins) {
        try {
            File cfgFile = new File("ashlar.yml");
            YamlConfiguration y = YamlConfiguration.loadConfiguration(cfgFile);
            if (!y.contains("multi-version.enabled")) {
                y.set("multi-version.enabled", true);
                y.setComments("multi-version", List.of(
                    "Lets players on other game versions join (old clients down to 1.8 and newer ones).",
                    "Uses the ViaVersion, ViaBackwards and ViaRewind plugins shipped with Ashlar."));
                y.save(cfgFile);
            }
            boolean on = y.getBoolean("multi-version.enabled", true);

            Files.createDirectories(plugins);
            Path marker = plugins.resolve(MARKER);
            Set<String> ours = new LinkedHashSet<>();
            if (Files.isRegularFile(marker)) ours.addAll(Files.readAllLines(marker, StandardCharsets.UTF_8));
            List<String> present = new ArrayList<>();
            try (Stream<Path> s = Files.list(plugins)) {
                s.map(p -> p.getFileName().toString()).filter(n -> n.toLowerCase(Locale.ROOT).endsWith(".jar")).forEach(present::add);
            }

            Set<String> now = new LinkedHashSet<>();
            for (String jar : JARS) {
                String prefix = jar.substring(0, jar.indexOf('-')).toLowerCase(Locale.ROOT);
                // remove an older copy that Ashlar put there itself
                for (String name : present) {
                    if (ours.contains(name) && name.toLowerCase(Locale.ROOT).startsWith(prefix) && (!on || !name.equals(jar))) {
                        Files.deleteIfExists(plugins.resolve(name));
                    }
                }
                if (!on) continue;
                boolean userCopy = present.stream().anyMatch(n -> !ours.contains(n) && n.toLowerCase(Locale.ROOT).startsWith(prefix));
                if (userCopy) continue;
                Path to = plugins.resolve(jar);
                if (!Files.isRegularFile(to)) {
                    try (InputStream in = Bundled.class.getResourceAsStream("/ashlar/bundled/" + jar)) {
                        if (in == null) continue;
                        Files.copy(in, to, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                now.add(jar);
            }
            Files.write(marker, now, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Ashlar.LOG.warn("Could not set up the bundled multi-version plugins: {}", e.getMessage());
        }
    }
}
