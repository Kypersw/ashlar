package org.craftrank.ashlar;

import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Watches what plugins do at run time. A plugin cannot make someone an operator or hand out every permission
 * through a console command unless the owner allowed that plugin in ashlar.yml; this is how most backdoors in
 * leaked plugins work. It also notes which outside addresses each plugin connects to.
 * The server calls allowOp and allowCommand (see the Ashlar patches); everything else lives here.
 */
public final class PluginGuard {

    private static final StackWalker WALKER = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private static volatile boolean enabled = true, blockOp = true, watchNet = true;
    private static volatile Set<String> allowed = Set.of();
    private static final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private static final Map<String, Set<String>> hosts = new ConcurrentHashMap<>();
    static final AtomicInteger blocked = new AtomicInteger();
    private static boolean netHooked;

    private PluginGuard() {
    }

    static void configure(AshlarConfig cfg) {
        enabled = cfg.pluginGuard;
        blockOp = cfg.pluginGuardBlockOp;
        watchNet = cfg.pluginGuardNet;
        Set<String> a = new LinkedHashSet<>();
        for (String s : cfg.pluginGuardAllowed) a.add(s.toLowerCase(Locale.ROOT));
        allowed = a;
        if (enabled && watchNet && !netHooked) hookNetwork();
    }

    /** The first plugin on the call stack, or null when the server or a person did it. */
    static String caller() {
        return WALKER.walk(s -> s.map(f -> f.getDeclaringClass().getClassLoader())
            .filter(cl -> cl instanceof ConfiguredPluginClassLoader)
            .findFirst()
            .map(cl -> ((ConfiguredPluginClassLoader) cl).getConfiguration().getName())
            .orElse(null));
    }

    private static boolean trusted(String plugin) {
        return allowed.contains(plugin.toLowerCase(Locale.ROOT));
    }

    /** Called before anyone is added to ops.json. */
    public static boolean allowOp(String name) {
        if (!enabled || !blockOp) return true;
        String p = caller();
        if (p == null || trusted(p)) return true;
        report(p, "tried to make " + name + " an operator");
        return false;
    }

    /** Called before a command runs. Only commands a plugin sends are checked. */
    public static boolean allowCommand(CommandSender sender, String line) {
        if (!enabled || !blockOp) return true;
        String why = dangerous(line);
        if (why == null) return true;
        String p = caller();
        if (p == null || trusted(p)) return true;
        report(p, "ran \"" + clip(line) + "\" (" + why + ")");
        return false;
    }

    /** Commands that give someone operator rights or every permission. */
    static String dangerous(String line) {
        String c = line.trim();
        if (c.startsWith("/")) c = c.substring(1);
        String[] a = c.toLowerCase(Locale.ROOT).split("\\s+");
        if (a.length == 0) return null;
        String label = a[0].contains(":") ? a[0].substring(a[0].indexOf(':') + 1) : a[0];
        if (label.equals("op") && a.length > 1) return "gives operator";
        boolean permPlugin = Set.of("lp", "luckperms", "lpb", "lpv", "perm", "perms", "permission", "permissions",
            "pex", "manuaddp", "mangaddp", "upa").contains(label);
        if (!permPlugin) return null;
        for (int i = 1; i < a.length; i++) {
            String n = a[i].replace("'", "").replace("\"", "");
            if (n.equals("*") || n.equals("luckperms.*") || n.equals("minecraft.command.op") || n.equals("minecraft.command.*")
                || n.equals("bukkit.command.op.give")) {
                return "gives every permission";
            }
        }
        return null;
    }

    private static void report(String plugin, String what) {
        blocked.incrementAndGet();
        String line = "Blocked: plugin " + plugin + " " + what + ". If this is expected, add " + plugin
            + " to security.plugin-guard.allowed-plugins in ashlar.yml.";
        Ashlar.LOG.warn(line);
        remember(line);
        Component msg = Component.text("[Ashlar] Plugin " + plugin + " " + what + ". It was blocked, see the console.", NamedTextColor.RED);
        Runnable tell = () -> {
            for (Player pl : Bukkit.getOnlinePlayers()) if (pl.hasPermission("ashlar.admin")) pl.sendMessage(msg);
        };
        if (Bukkit.isPrimaryThread()) tell.run();
        else if (Ashlar.plugin() != null) Bukkit.getScheduler().runTask(Ashlar.plugin(), tell);
    }

    static void remember(String line) {
        synchronized (events) {
            events.add(java.time.LocalTime.now().withNano(0) + " " + line);
            while (events.size() > 50) events.remove(0);
        }
    }

    private static String clip(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "..." : s;
    }

    // ---------- outside connections ----------

    private static void hookNetwork() {
        ProxySelector prev = ProxySelector.getDefault();
        if (prev == null) return;
        netHooked = true;
        ProxySelector.setDefault(new ProxySelector() {
            @Override
            public List<Proxy> select(URI uri) {
                try {
                    if (watchNet && enabled) note(uri);
                } catch (RuntimeException ignored) {
                    // never break a connection because of the watcher
                }
                return prev.select(uri);
            }

            @Override
            public void connectFailed(URI uri, SocketAddress sa, IOException e) {
                prev.connectFailed(uri, sa, e);
            }
        });
    }

    private static void note(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isEmpty() || host.equals("localhost")) return;
        if (host.matches("[0-9.]+") || host.contains(":")) {
            try {
                InetAddress a = InetAddress.getByName(host);
                if (a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()) return;
            } catch (Exception e) {
                return;
            }
        }
        String p = caller();
        if (p == null) return;
        Set<String> s = hosts.computeIfAbsent(p, k -> ConcurrentHashMap.newKeySet());
        if (s.size() < 40 && s.add(host)) {
            Ashlar.LOG.info("Plugin {} connected to {}", p, host + (uri.getPort() > 0 ? ":" + uri.getPort() : ""));
        }
    }

    // ---------- reports ----------

    static List<String> report() {
        List<String> l = new ArrayList<>();
        Map<String, List<String>> scan = PluginScan.results();
        Set<String> names = new LinkedHashSet<>(scan.keySet());
        Map<String, String> byPlugin = new LinkedHashMap<>();
        for (var e : hosts.entrySet()) byPlugin.put(e.getKey(), String.join(", ", e.getValue()));
        for (String n : names) {
            for (String f : scan.get(n)) l.add(n + ": " + f);
        }
        for (var e : byPlugin.entrySet()) l.add(e.getKey() + " connects to " + e.getValue());
        synchronized (events) {
            l.addAll(events);
        }
        if (l.isEmpty()) l.add("Nothing unusual found.");
        return l;
    }

    static String status() {
        if (!enabled) return "off";
        return "on, blocked " + blocked.get() + ", flagged plugins " + PluginScan.flagged();
    }
}
