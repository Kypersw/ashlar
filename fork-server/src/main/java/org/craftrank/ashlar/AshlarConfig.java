package org.craftrank.ashlar;

import java.io.File;
import java.io.IOException;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/** ashlar.yml in the server folder. Every feature can be switched off here. */
public final class AshlarConfig {

    public final File file;
    private YamlConfiguration yml;

    // performance
    public boolean dynDistance;
    public int dynMin, dynMax, dynCheckSeconds;
    public double dynHighMspt, dynLowMspt;

    // security
    public boolean antiBot;
    public int antiBotJoinsPerSecond, antiBotPerIpPerMinute, antiBotLockMinutes;
    public boolean nameCheck;
    public boolean cmdLimit;
    public int cmdPerSecond, chatPerSecond, spamKickAfter;
    public boolean hidePlugins;
    public List<String> hiddenCommands;

    // votes (Votifier v2 protocol)
    public boolean votes;
    public String voteHost;
    public int votePort;
    public String voteToken;
    public List<String> voteCommands;
    public String voteBroadcast;
    public boolean voteOffline;

    // chunk pre-generation while nobody is online
    public boolean pregen;
    public int pregenRadius, pregenParallel;
    public List<String> pregenWorlds;

    // exploit guards
    public boolean exploits;
    public int bookMaxPages, bookMaxPageChars, signMaxChars, tabPerSecond, chunkEntityCap, chunkTntCap;

    // temporary IP bans and the optional VPN/proxy check
    public boolean autoBan;
    public boolean pluginGuard, pluginGuardBlockOp, pluginGuardNet, pluginGuardScan;
    public List<String> pluginGuardAllowed = List.of();
    public boolean connGuard;
    public int connPerIpPerSecond, connOpenPerIp, connAttackPerSecond, connAttackMinutes, connFirstPacketSeconds, connMaxBytesPerSecond;
    public int autoBanAfter, autoBanMinutes;
    public boolean vpnCheck;
    public String vpnKey;
    public boolean vpnBlockHosting;

    // login for offline-mode servers
    public String login;   // auto | on | off
    public int loginTimeout, loginSessionMinutes, loginMinLength, loginMaxPerIp, loginMaxTries;

    // backups
    public boolean backups;
    public int backupHours, backupKeep;
    public String backupFolder;

    // web panel
    public boolean panel;
    public String panelHost;
    public int panelPort;
    public String panelToken;
    public boolean panelCommands;

    // updates
    public boolean updateCheck, updateDownload;
    public int updateHours;

    // Bedrock (Geyser + Floodgate)
    public boolean bedrock;

    public AshlarConfig(File file) {
        this.file = file;
    }

    public void load() {
        this.yml = YamlConfiguration.loadConfiguration(this.file);
        YamlConfiguration y = this.yml;
        y.options().setHeader(List.of(
            "Ashlar settings. Run /ashlar reload after editing.",
            "Every feature can be turned off with enabled: false."));

        this.dynDistance = def(y, "performance.dynamic-distance.enabled", true);
        this.dynMin = def(y, "performance.dynamic-distance.min-simulation-distance", 4);
        this.dynMax = def(y, "performance.dynamic-distance.max-simulation-distance", 10);
        this.dynHighMspt = def(y, "performance.dynamic-distance.lower-when-mspt-above", 45.0);
        this.dynLowMspt = def(y, "performance.dynamic-distance.raise-when-mspt-below", 30.0);
        this.dynCheckSeconds = Math.max(2, def(y, "performance.dynamic-distance.check-every-seconds", 10));

        this.antiBot = def(y, "security.anti-bot.enabled", true);
        this.antiBotJoinsPerSecond = Math.max(1, def(y, "security.anti-bot.max-new-players-per-second", 6));
        this.antiBotPerIpPerMinute = Math.max(1, def(y, "security.anti-bot.max-joins-per-ip-per-minute", 8));
        this.antiBotLockMinutes = Math.max(1, def(y, "security.anti-bot.lockdown-minutes", 5));
        this.nameCheck = def(y, "security.name-check", true);
        this.cmdLimit = def(y, "security.spam-limit.enabled", true);
        this.cmdPerSecond = Math.max(1, def(y, "security.spam-limit.commands-per-second", 6));
        this.chatPerSecond = Math.max(1, def(y, "security.spam-limit.chat-per-second", 4));
        this.spamKickAfter = Math.max(1, def(y, "security.spam-limit.kick-after-blocked", 25));
        this.hidePlugins = def(y, "security.hide-plugins.enabled", true);
        this.hiddenCommands = defList(y, "security.hide-plugins.commands",
            List.of("plugins", "pl", "version", "ver", "about", "icanhasbukkit", "bukkit:plugins", "bukkit:pl",
                "bukkit:version", "bukkit:ver", "bukkit:about", "bukkit:help", "bukkit:?", "paper:version"));

        this.votes = def(y, "votes.enabled", false);
        this.voteHost = def(y, "votes.host", "0.0.0.0");
        this.votePort = def(y, "votes.port", 8192);
        String token = def(y, "votes.token", "");
        if (token.isBlank()) {
            token = AshlarVotes.newToken();
            y.set("votes.token", token);
        }
        this.voteToken = token;
        this.voteCommands = defList(y, "votes.commands", List.of("give %player% diamond 1"));
        this.voteOffline = def(y, "votes.reward-offline-players-on-join", true);
        this.voteBroadcast = def(y, "votes.broadcast", "&6%player% &7voted on &6%service% &7and got a reward!");

        this.pregen = def(y, "performance.pregen.enabled", true);
        this.pregenRadius = Math.max(64, def(y, "performance.pregen.radius-blocks", 1000));
        this.pregenParallel = Math.max(1, Math.min(32, def(y, "performance.pregen.chunks-at-once", 8)));
        this.pregenWorlds = defList(y, "performance.pregen.worlds", List.of("world"));

        this.exploits = def(y, "security.exploit-guard.enabled", true);
        this.bookMaxPages = Math.max(1, def(y, "security.exploit-guard.book-max-pages", 50));
        this.bookMaxPageChars = Math.max(16, def(y, "security.exploit-guard.book-max-page-chars", 320));
        this.signMaxChars = Math.max(8, def(y, "security.exploit-guard.sign-max-chars-per-line", 80));
        this.tabPerSecond = Math.max(2, def(y, "security.exploit-guard.tab-complete-per-second", 15));
        this.chunkEntityCap = Math.max(50, def(y, "security.exploit-guard.max-entities-per-chunk", 300));
        this.chunkTntCap = Math.max(10, def(y, "security.exploit-guard.max-tnt-and-falling-blocks-per-chunk", 80));

        this.autoBan = def(y, "security.auto-ban.enabled", true);
        this.autoBanAfter = Math.max(2, def(y, "security.auto-ban.after-blocked-joins", 20));
        this.autoBanMinutes = Math.max(1, def(y, "security.auto-ban.minutes", 60));

        this.pluginGuard = def(y, "security.plugin-guard.enabled", true);
        this.pluginGuardBlockOp = def(y, "security.plugin-guard.block-op-and-full-permissions", true);
        this.pluginGuardAllowed = defList(y, "security.plugin-guard.allowed-plugins", List.of());
        this.pluginGuardScan = def(y, "security.plugin-guard.check-jars-on-start", true);
        this.pluginGuardNet = def(y, "security.plugin-guard.log-outside-connections", true);
        y.setComments("security.plugin-guard", List.of(
            "Stops plugins from making players operators or giving them every permission (how most backdoors in leaked plugins work).",
            "On start it also checks plugin jars for code that runs programs, downloads code or hides web addresses, and it logs",
            "which outside addresses each plugin connects to. Put plugins that really need to give op in allowed-plugins."));

        this.connGuard = def(y, "security.connection-guard.enabled", true);
        this.connPerIpPerSecond = Math.max(1, def(y, "security.connection-guard.new-connections-per-ip-per-second", 6));
        this.connOpenPerIp = Math.max(1, def(y, "security.connection-guard.open-connections-per-ip", 20));
        this.connAttackPerSecond = Math.max(10, def(y, "security.connection-guard.attack-at-new-connections-per-second", 150));
        this.connAttackMinutes = Math.max(1, def(y, "security.connection-guard.attack-mode-minutes", 2));
        this.connFirstPacketSeconds = Math.max(1, def(y, "security.connection-guard.silent-connection-seconds", 5));
        this.connMaxBytesPerSecond = Math.max(4096, def(y, "security.connection-guard.max-bytes-per-second-before-join", 131072));
        y.setComments("security.connection-guard", List.of(
            "Connection flood protection, checked before Minecraft reads anything. Banned addresses are dropped at once,",
            "each address has a limit on new and open connections, and silent or very chatty connections are closed.",
            "Above attack-at-new-connections-per-second only players who joined before can connect for a few minutes.",
            "Off by itself behind BungeeCord/Velocity. Floods that fill the network line must be stopped by your host;",
            "ashlar-blocked-ips.txt lists banned addresses for a firewall."));
        this.vpnCheck = def(y, "security.vpn-check.enabled", false);
        this.vpnKey = def(y, "security.vpn-check.proxycheck-key", "");
        this.vpnBlockHosting = def(y, "security.vpn-check.block-hosting-providers", true);

        this.login = def(y, "login.enabled", "auto");
        this.loginTimeout = Math.max(15, def(y, "login.timeout-seconds", 60));
        this.loginSessionMinutes = Math.max(0, def(y, "login.session-minutes", 30));
        this.loginMinLength = Math.max(4, def(y, "login.min-password-length", 6));
        this.loginMaxPerIp = Math.max(1, def(y, "login.max-accounts-per-ip", 3));
        this.loginMaxTries = Math.max(1, def(y, "login.max-wrong-passwords", 5));

        this.backups = def(y, "backups.enabled", true);
        this.backupHours = Math.max(1, def(y, "backups.every-hours", 6));
        this.backupKeep = Math.max(1, def(y, "backups.keep", 8));
        this.backupFolder = def(y, "backups.folder", "backups");

        this.panel = def(y, "web-panel.enabled", true);
        this.panelHost = def(y, "web-panel.host", "127.0.0.1");
        this.panelPort = def(y, "web-panel.port", 8180);
        String pt = def(y, "web-panel.token", "");
        if (pt.isBlank()) {
            pt = AshlarVotes.newToken();
            y.set("web-panel.token", pt);
        }
        this.panelToken = pt;
        this.panelCommands = def(y, "web-panel.allow-console-commands", true);

        this.updateCheck = def(y, "updates.check", true);
        this.updateDownload = def(y, "updates.download", true);
        this.updateHours = Math.max(1, def(y, "updates.check-every-hours", 12));

        this.bedrock = def(y, "bedrock.enabled", true);

        y.setComments("security.vpn-check", List.of("Asks proxycheck.io whether a new player's address is a VPN, proxy or hosting provider.",
            "Off by default: it sends that address to proxycheck.io. A free key gives 1000 checks a day."));
        y.setComments("login", List.of("Register/login for offline-mode (cracked) servers. auto = on only when online-mode is false.",
            "Bedrock players who join through Floodgate are signed in by Xbox and skip it."));
        y.setComments("web-panel", List.of("Browser panel: http://host:port. The token signs you in; /ashlar panel shows it in the console.",
            "Keep host 127.0.0.1 and use an SSH tunnel or an HTTPS reverse proxy to reach it from outside."));
        y.setComments("updates", List.of("Checks github.com/Kypersw/ashlar for a newer build of the same Minecraft version.",
            "A download is checked against GitHub's SHA-256 and put in place when the server stops."));

        try {
            y.save(this.file);
        } catch (IOException e) {
            Ashlar.LOG.warn("Could not save ashlar.yml: {}", e.getMessage());
        }
    }

    /** A true/false setting straight from the file (used by the web panel). */
    public boolean flag(String path) {
        return YamlConfiguration.loadConfiguration(this.file).getBoolean(path);
    }

    /** Writes one setting to ashlar.yml, keeping the rest; call load() after. */
    public void write(String path, Object value) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(this.file);
        y.set(path, value);
        try {
            y.save(this.file);
        } catch (IOException e) {
            Ashlar.LOG.warn("Could not save ashlar.yml: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T def(YamlConfiguration y, String path, T value) {
        if (!y.contains(path)) {
            y.set(path, value);
            return value;
        }
        Object o = y.get(path);
        if (value instanceof Integer) return (T) Integer.valueOf(y.getInt(path));
        if (value instanceof Double) return (T) Double.valueOf(y.getDouble(path));
        if (value instanceof Boolean) return (T) Boolean.valueOf(y.getBoolean(path));
        if (value instanceof String) return (T) String.valueOf(o);
        return value;
    }

    private static List<String> defList(YamlConfiguration y, String path, List<String> value) {
        if (!y.contains(path)) {
            y.set(path, value);
            return value;
        }
        return y.getStringList(path);
    }
}
