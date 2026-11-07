package org.craftrank.ashlar;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * A small browser panel built into the server: status, players, console, backups and the main switches.
 * Sign-in is the token from ashlar.yml; it gives an HttpOnly session cookie. Every change needs a POST with
 * the X-Ashlar header (a page on another site cannot send it), and failed sign-ins are rate limited.
 */
final class WebPanel {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Gson GSON = new Gson();
    private static final long SESSION_MS = 12L * 3600_000;
    /** Switches the panel may flip; anything else in ashlar.yml stays file-only. */
    private static final List<String> SWITCHES = List.of("performance.dynamic-distance.enabled", "performance.pregen.enabled",
        "security.anti-bot.enabled", "security.spam-limit.enabled", "security.hide-plugins.enabled", "security.exploit-guard.enabled",
        "security.auto-ban.enabled", "security.vpn-check.enabled", "security.connection-guard.enabled", "security.plugin-guard.enabled", "votes.enabled", "backups.enabled", "updates.check");

    private final Ashlar a;
    private final AshlarConfig cfg;
    private final Plugin plugin;
    private final Map<String, Long> sessions = new ConcurrentHashMap<>();
    private final Map<String, long[]> fails = new ConcurrentHashMap<>();
    private final ArrayDeque<String[]> log = new ArrayDeque<>();
    private long logSeq;
    private HttpServer server;
    private AbstractAppender appender;
    volatile String state = "off";

    WebPanel(Ashlar a, AshlarConfig cfg, Plugin plugin) {
        this.a = a;
        this.cfg = cfg;
        this.plugin = plugin;
    }

    void start() {
        stop();
        if (!this.cfg.panel) {
            this.state = "off";
            return;
        }
        try {
            HttpServer s = HttpServer.create(new InetSocketAddress(this.cfg.panelHost, this.cfg.panelPort), 32);
            s.setExecutor(Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "Ashlar panel");
                t.setDaemon(true);
                return t;
            }));
            s.createContext("/", this::handle);
            s.start();
            this.server = s;
            hookLog();
            this.state = "http://" + this.cfg.panelHost + ":" + this.cfg.panelPort;
            Ashlar.LOG.info("Web panel on {} (sign in with the token from /ashlar panel)", this.state);
        } catch (IOException e) {
            this.state = "failed: " + e.getMessage();
            Ashlar.LOG.warn("Web panel could not open port {}: {}", this.cfg.panelPort, e.getMessage());
        }
    }

    void stop() {
        if (this.server != null) this.server.stop(0);
        this.server = null;
        if (this.appender != null) {
            ((Logger) LogManager.getRootLogger()).removeAppender(this.appender);
            this.appender.stop();
            this.appender = null;
        }
    }

    private void hookLog() {
        AbstractAppender ap = new AbstractAppender("AshlarPanel", null, null, true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent e) {
                String msg = e.getMessage().getFormattedMessage().replaceAll("\u001B\\[[;\\d]*m", "");
                String line = String.format(Locale.ROOT, "[%tT %s] %s", e.getTimeMillis(), e.getLevel().name(), msg);
                synchronized (WebPanel.this.log) {
                    WebPanel.this.log.addLast(new String[] {String.valueOf(++WebPanel.this.logSeq), line});
                    while (WebPanel.this.log.size() > 600) WebPanel.this.log.removeFirst();
                }
            }
        };
        ap.start();
        ((Logger) LogManager.getRootLogger()).addAppender(ap);
        this.appender = ap;
    }

    // ---------- HTTP ----------

    private void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath();
            String m = x.getRequestMethod();
            x.getResponseHeaders().set("X-Frame-Options", "DENY");
            x.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            x.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            x.getResponseHeaders().set("Cache-Control", "no-store");
            switch (path) {
                case "/" -> send(x, 200, "text/html; charset=utf-8", res("panel.html"));
                case "/panel.css" -> send(x, 200, "text/css; charset=utf-8", res("panel.css"));
                case "/panel.js" -> send(x, 200, "text/javascript; charset=utf-8", res("panel.js"));
                case "/api/login" -> login(x, m);
                case "/api/logout" -> {
                    if (post(x, m)) {
                        String sid = cookie(x);
                        if (sid != null) this.sessions.remove(sid);
                        x.getResponseHeaders().add("Set-Cookie", "ashlar=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict");
                        json(x, 200, Map.of("ok", true));
                    }
                }
                default -> {
                    if (!path.startsWith("/api/")) {
                        send(x, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
                    } else if (!authed(x)) {
                        json(x, 401, Map.of("error", "Sign in first."));
                    } else {
                        api(x, path, m);
                    }
                }
            }
        } catch (Exception e) {
            json(x, 500, Map.of("error", "Something went wrong."));
        } finally {
            x.close();
        }
    }

    private void login(HttpExchange x, String m) throws IOException {
        if (!post(x, m)) return;
        String ip = x.getRemoteAddress().getAddress().getHostAddress();
        long now = System.currentTimeMillis();
        long[] f = this.fails.computeIfAbsent(ip, k -> new long[2]);
        synchronized (f) {
            if (now - f[0] > 60_000) {
                f[0] = now;
                f[1] = 0;
            }
            if (f[1] >= 5) {
                json(x, 429, Map.of("error", "Too many tries. Wait a minute."));
                return;
            }
        }
        JsonObject body = body(x);
        String token = body.has("token") ? body.get("token").getAsString().trim() : "";
        if (!MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), this.cfg.panelToken.getBytes(StandardCharsets.UTF_8))) {
            synchronized (f) {
                f[1]++;
            }
            json(x, 403, Map.of("error", "Wrong token."));
            return;
        }
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        String sid = HexFormat.of().formatHex(b);
        this.sessions.put(sid, now + SESSION_MS);
        this.sessions.entrySet().removeIf(e -> e.getValue() < now);
        x.getResponseHeaders().add("Set-Cookie", "ashlar=" + sid + "; Path=/; Max-Age=" + SESSION_MS / 1000 + "; HttpOnly; SameSite=Strict");
        json(x, 200, Map.of("ok", true));
    }

    private void api(HttpExchange x, String path, String m) throws Exception {
        switch (path) {
            case "/api/status" -> json(x, 200, onMain(this::status));
            case "/api/console" -> {
                long after = 0;
                String q = x.getRequestURI().getQuery();
                if (q != null && q.startsWith("after=")) after = Long.parseLong(q.substring(6).replaceAll("\\D", "").isEmpty() ? "0" : q.substring(6).replaceAll("\\D", ""));
                List<String[]> out = new ArrayList<>();
                synchronized (this.log) {
                    for (String[] l : this.log) if (Long.parseLong(l[0]) > after) out.add(l);
                }
                json(x, 200, Map.of("lines", out, "commands", this.cfg.panelCommands));
            }
            case "/api/command" -> {
                if (!post(x, m)) return;
                if (!this.cfg.panelCommands) {
                    json(x, 403, Map.of("error", "Console commands are turned off in ashlar.yml."));
                    return;
                }
                String cmd = body(x).get("command").getAsString().trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (cmd.isEmpty() || cmd.length() > 300 || cmd.contains("\n")) {
                    json(x, 400, Map.of("error", "Type a command."));
                    return;
                }
                String c = cmd;
                Ashlar.LOG.info("Web panel ran: {}", Ashlar.forLog("/" + c));
                onMain(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c));
                json(x, 200, Map.of("ok", true));
            }
            case "/api/switch" -> {
                if (!post(x, m)) return;
                JsonObject b = body(x);
                String key = b.get("key").getAsString();
                boolean on = b.get("on").getAsBoolean();
                if (!SWITCHES.contains(key)) {
                    json(x, 400, Map.of("error", "Unknown setting."));
                    return;
                }
                onMain(() -> {
                    this.a.setSwitch(key, on);
                    return true;
                });
                json(x, 200, Map.of("ok", true));
            }
            case "/api/backup" -> {
                if (!post(x, m)) return;
                onMain(() -> this.a.backup.run(r -> Ashlar.LOG.info("Web panel backup: {}", r)));
                json(x, 200, Map.of("ok", true));
            }
            case "/api/kick" -> {
                if (!post(x, m)) return;
                String name = body(x).get("name").getAsString();
                onMain(() -> {
                    Player p = Bukkit.getPlayerExact(name);
                    if (p != null) p.kick(net.kyori.adventure.text.Component.text("Kicked from the server panel."));
                    return true;
                });
                json(x, 200, Map.of("ok", true));
            }
            default -> json(x, 404, Map.of("error", "Not found."));
        }
    }

    private Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        double[] tps = Bukkit.getTPS();
        Runtime rt = Runtime.getRuntime();
        s.put("version", Bukkit.getMinecraftVersion());
        s.put("tps", Math.round(tps[0] * 10) / 10.0);
        s.put("mspt", Math.round(Bukkit.getAverageTickTime() * 10) / 10.0);
        s.put("memUsed", (rt.totalMemory() - rt.freeMemory()) >> 20);
        s.put("memMax", rt.maxMemory() >> 20);
        List<Map<String, Object>> ps = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            ps.add(Map.of("name", p.getName(), "ping", p.getPing(), "world", p.getWorld().getName()));
        }
        s.put("players", ps);
        s.put("maxPlayers", Bukkit.getMaxPlayers());
        List<Map<String, Object>> ws = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            ws.add(Map.of("name", w.getName(), "chunks", w.getLoadedChunks().length, "entities", w.getEntityCount(), "sim", w.getSimulationDistance()));
        }
        s.put("worlds", ws);
        Map<String, Boolean> sw = new LinkedHashMap<>();
        for (String k : SWITCHES) sw.put(k, this.a.getSwitch(k));
        s.put("switches", sw);
        s.put("lines", this.a.status());
        List<String> bk = new ArrayList<>();
        for (var f : this.a.backup.list()) bk.add(f.getName() + "  " + (f.length() >> 20) + " MB");
        s.put("backups", bk);
        return s;
    }

    // ---------- helpers ----------

    private <T> T onMain(java.util.concurrent.Callable<T> c) throws Exception {
        return Bukkit.getScheduler().callSyncMethod(this.plugin, c).get(10, TimeUnit.SECONDS);
    }

    private boolean authed(HttpExchange x) {
        String sid = cookie(x);
        if (sid == null) return false;
        Long t = this.sessions.get(sid);
        return t != null && t > System.currentTimeMillis();
    }

    private static String cookie(HttpExchange x) {
        for (String h : x.getRequestHeaders().getOrDefault("Cookie", List.of())) {
            for (String part : h.split(";")) {
                String p = part.trim();
                if (p.startsWith("ashlar=")) return p.substring(7);
            }
        }
        return null;
    }

    /** POST with our header only, so another website cannot make a signed-in browser change anything. */
    private static boolean post(HttpExchange x, String m) throws IOException {
        if (!"POST".equals(m) || !"1".equals(x.getRequestHeaders().getFirst("X-Ashlar"))) {
            json(x, 405, Map.of("error", "Use the panel."));
            return false;
        }
        return true;
    }

    private static JsonObject body(HttpExchange x) throws IOException {
        try (InputStream in = x.getRequestBody()) {
            byte[] b = in.readNBytes(16_384);
            return JsonParser.parseString(new String(b, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static byte[] res(String name) throws IOException {
        try (InputStream in = WebPanel.class.getResourceAsStream("/ashlar/panel/" + name)) {
            if (in == null) throw new IOException("missing " + name);
            return in.readAllBytes();
        }
    }

    private static void json(HttpExchange x, int code, Object o) throws IOException {
        send(x, code, "application/json; charset=utf-8", GSON.toJson(o).getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange x, int code, String type, byte[] b) throws IOException {
        x.getResponseHeaders().set("Content-Type", type);
        if (type.startsWith("text/html")) {
            x.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; img-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
        }
        if ("HEAD".equals(x.getRequestMethod())) {
            x.sendResponseHeaders(code, -1);
            return;
        }
        x.sendResponseHeaders(code, b.length);
        try (OutputStream o = x.getResponseBody()) {
            o.write(b);
        }
    }
}
