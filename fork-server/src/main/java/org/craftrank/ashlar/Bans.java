package org.craftrank.ashlar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

/**
 * Temporary IP bans that survive restarts (ashlar-bans.yml): addresses that keep getting turned away by
 * the anti-bot or that guess passwords are banned for a while. Optionally asks proxycheck.io whether a
 * new player comes from a VPN, proxy or hosting provider.
 */
final class Bans implements Listener {

    private final AshlarConfig cfg;
    private final File file = new File("ashlar-bans.yml");
    private final Map<String, Long> until = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> strikes = new ConcurrentHashMap<>();
    private final Map<String, Object[]> vpnCache = new ConcurrentHashMap<>();   // ip -> {Boolean bad, Long checkedAt}
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    final AtomicInteger blocked = new AtomicInteger();
    final AtomicInteger vpnBlocked = new AtomicInteger();

    Bans(AshlarConfig cfg) {
        this.cfg = cfg;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(this.file);
        long now = System.currentTimeMillis();
        for (String k : y.getKeys(false)) {
            long t = y.getLong(k);
            if (t > now) this.until.put(k.replace('_', '.').replace('-', ':'), t);
        }
    }

    private synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        long now = System.currentTimeMillis();
        this.until.forEach((ip, t) -> {
            if (t > now) y.set(ip.replace('.', '_').replace(':', '-'), t);
        });
        try {
            y.save(this.file);
        } catch (IOException e) {
            Ashlar.LOG.warn("Could not save ashlar-bans.yml: {}", e.getMessage());
        }
    }

    void ban(String ip, int minutes, String why) {
        this.until.put(ip, System.currentTimeMillis() + minutes * 60_000L);
        this.strikes.remove(ip);
        save();
        Ashlar.LOG.warn("Banned {} for {} minutes ({})", ip, minutes, why);
    }

    boolean unban(String ip) {
        boolean had = this.until.remove(ip) != null;
        this.strikes.remove(ip);
        save();
        return had;
    }

    List<String> list() {
        List<String> l = new ArrayList<>();
        long now = System.currentTimeMillis();
        this.until.forEach((ip, t) -> {
            if (t > now) l.add(ip + "  " + ((t - now) / 60_000 + 1) + " min left");
        });
        return l;
    }

    int count() {
        long now = System.currentTimeMillis();
        return (int) this.until.values().stream().filter(t -> t > now).count();
    }

    /** One more blocked join or wrong password from this address; enough of them means a ban. */
    void strike(InetAddress a, String why) {
        if (!this.cfg.autoBan || a.isLoopbackAddress()) return;
        String ip = a.getHostAddress();
        int n = this.strikes.computeIfAbsent(ip, k -> new AtomicInteger()).incrementAndGet();
        if (n >= this.cfg.autoBanAfter) ban(ip, this.cfg.autoBanMinutes, why);
        if (this.strikes.size() > 20_000) this.strikes.clear();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        String ip = e.getAddress().getHostAddress();
        Long t = this.until.get(ip);
        if (t != null) {
            if (t > System.currentTimeMillis()) {
                this.blocked.incrementAndGet();
                long min = (t - System.currentTimeMillis()) / 60_000 + 1;
                e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                    Component.text("Your address is blocked for " + min + " more minute" + (min == 1 ? "" : "s") + ".", NamedTextColor.RED));
                return;
            }
            this.until.remove(ip);
        }
        if (this.cfg.vpnCheck && !isLocal(e.getAddress()) && !Bukkit.getOfflinePlayer(e.getUniqueId()).hasPlayedBefore() && isVpn(ip)) {
            this.vpnBlocked.incrementAndGet();
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("VPNs and proxies are not allowed for new players on this server.", NamedTextColor.RED));
        }
    }

    private static boolean isLocal(InetAddress a) {
        return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress();
    }

    /** proxycheck.io v2; a failed or slow check lets the player in. Answers are kept for a day. */
    private boolean isVpn(String ip) {
        Object[] c = this.vpnCache.get(ip);
        if (c != null && System.currentTimeMillis() - (Long) c[1] < 86_400_000L) return (Boolean) c[0];
        try {
            String url = "https://proxycheck.io/v2/" + URLEncoder.encode(ip, StandardCharsets.UTF_8) + "?vpn=1&asn=1"
                + (this.cfg.vpnKey.isBlank() ? "" : "&key=" + URLEncoder.encode(this.cfg.vpnKey, StandardCharsets.UTF_8));
            HttpResponse<String> r = this.http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4))
                .header("User-Agent", "Ashlar").GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonObject o = JsonParser.parseString(r.body()).getAsJsonObject();
            if (!o.has(ip)) return false;
            JsonObject d = o.getAsJsonObject(ip);
            String proxy = d.has("proxy") ? d.get("proxy").getAsString() : "no";
            String type = d.has("type") ? d.get("type").getAsString() : "";
            boolean bad = "yes".equalsIgnoreCase(proxy) || (this.cfg.vpnBlockHosting && "hosting".equalsIgnoreCase(type));
            this.vpnCache.put(ip, new Object[] {bad, System.currentTimeMillis()});
            if (this.vpnCache.size() > 50_000) this.vpnCache.clear();
            return bad;
        } catch (Exception ex) {
            return false;
        }
    }
}
