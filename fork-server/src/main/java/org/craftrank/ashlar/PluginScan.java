package org.craftrank.ashlar;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Reads every plugin jar before plugins load and points out code that backdoors in leaked plugins use:
 * starting programs on the machine, downloading and running code, and addresses (often hidden in Base64)
 * where server data could be sent. It only reports; nothing is changed or removed.
 */
final class PluginScan {

    private static final Pattern SEND_TO = Pattern.compile(
        "(discord(app)?\\.com/api/webhooks/\\d+/[\\w-]+|api\\.telegram\\.org/bot|pastebin\\.com/raw|hastebin|ngrok(-free)?\\.(io|app)|transfer\\.sh|webhook\\.site)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern B64 = Pattern.compile("^[A-Za-z0-9+/]{16,}={0,2}$");
    private static final Map<String, List<String>> RESULTS = new ConcurrentHashMap<>();
    private static final String CACHE = ".ashlar-scan";

    private PluginScan() {
    }

    static Map<String, List<String>> results() {
        return RESULTS;
    }

    static int flagged() {
        return RESULTS.size();
    }

    /** Scans plugins/*.jar; results are cached by name, size and date so unchanged jars are not read again. */
    static void scan(Path plugins, Set<String> skip) {
        long t0 = System.currentTimeMillis();
        Path cacheFile = plugins.resolve(CACHE);
        Map<String, String> cache = new LinkedHashMap<>();
        try {
            if (Files.isRegularFile(cacheFile)) {
                for (String l : Files.readAllLines(cacheFile, StandardCharsets.UTF_8)) {
                    int i = l.indexOf('\t');
                    if (i > 0) cache.put(l.substring(0, i), l.substring(i + 1));
                }
            }
        } catch (IOException ignored) {
            // rebuilt below
        }
        Map<String, String> next = new LinkedHashMap<>();
        int read = 0;
        try (Stream<Path> s = Files.list(plugins)) {
            for (Path jar : s.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")).sorted().toList()) {
                String name = jar.getFileName().toString();
                if (skip.contains(name)) continue;
                String key = name + "|" + Files.size(jar) + "|" + Files.getLastModifiedTime(jar).toMillis();
                String found = cache.get(key);
                if (found == null) {
                    found = String.join("\u001f", scanJar(jar));
                    read++;
                }
                next.put(key, found);
                if (!found.isEmpty()) RESULTS.put(name, List.of(found.split("\u001f")));
            }
        } catch (IOException e) {
            Ashlar.LOG.warn("Could not check the plugins folder: {}", e.getMessage());
            return;
        }
        try {
            List<String> out = new ArrayList<>();
            next.forEach((k, v) -> out.add(k + "\t" + v));
            Files.write(cacheFile, out, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // the cache is only a speed-up
        }
        if (!RESULTS.isEmpty()) {
            Ashlar.LOG.warn("*".repeat(70));
            Ashlar.LOG.warn("Plugin check: these plugins contain code that backdoors often use.");
            Ashlar.LOG.warn("This does not prove a plugin is harmful. Make sure you got it from its real author.");
            RESULTS.forEach((n, l) -> l.forEach(f -> Ashlar.LOG.warn("  {}: {}", n, f)));
            Ashlar.LOG.warn("*".repeat(70));
        }
        if (read > 0) Ashlar.LOG.info("Checked {} plugin jar(s) in {} ms", read, System.currentTimeMillis() - t0);
    }

    static List<String> scanJar(Path jar) {
        Set<String> found = new LinkedHashSet<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            var en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".class") || e.getSize() > 4_000_000) continue;
                try (InputStream in = z.getInputStream(e)) {
                    scanClass(in.readAllBytes(), found);
                } catch (RuntimeException | IOException ignored) {
                    // a class ASM cannot read is skipped
                }
                if (found.size() > 8) break;
            }
        } catch (IOException e) {
            found.add("could not be read (" + e.getMessage() + ")");
        }
        return new ArrayList<>(found);
    }

    private static void scanClass(byte[] b, Set<String> found) {
        ClassReader r = new ClassReader(b);
        String cls = r.getClassName().replace('/', '.');
        boolean[] f = new boolean[3]; // exec, define class, network
        r.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public org.objectweb.asm.FieldVisitor visitField(int access, String name, String desc, String sig, Object value) {
                if (value instanceof String str) checkString(str, cls, found);
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) {
                        if ((owner.equals("java/lang/Runtime") && n.equals("exec")) || (owner.equals("java/lang/ProcessBuilder") && n.equals("start"))) f[0] = true;
                        if ((n.equals("defineClass") || n.equals("defineHiddenClass")) && d.endsWith(")Ljava/lang/Class;")) f[1] = true;
                        if ((owner.equals("java/net/URL") && (n.equals("openConnection") || n.equals("openStream")))
                            || (owner.equals("java/net/http/HttpClient") && n.startsWith("send"))
                            || (owner.equals("java/net/Socket") && n.equals("<init>"))) f[2] = true;
                    }

                    @Override
                    public void visitLdcInsn(Object v) {
                        if (v instanceof String s) checkString(s, cls, found);
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (f[0]) found.add("can start programs on this machine (" + cls + ")");
        if (f[1] && f[2]) found.add("can download code and run it (" + cls + ")");
    }

    private static void checkString(String s, String cls, Set<String> found) {
        if (s.length() < 8 || s.length() > 4000) return;
        var m = SEND_TO.matcher(s);
        if (m.find()) {
            found.add("has a hard-coded address that data can be sent to: " + shortHost(m.group()) + " (" + cls + ")");
            return;
        }
        if (B64.matcher(s).matches()) {
            try {
                String d = new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
                if (d.startsWith("http://") || d.startsWith("https://") || SEND_TO.matcher(d).find()) {
                    found.add("hides a web address in Base64: " + shortHost(d) + " (" + cls + ")");
                }
            } catch (IllegalArgumentException ignored) {
                // not Base64 after all
            }
        }
    }

    /** Only the host part, never the webhook id or token. */
    private static String shortHost(String u) {
        String h = u.replaceFirst("^[a-z]+://", "");
        int i = h.indexOf('/');
        return i > 0 ? h.substring(0, i) : h;
    }
}
