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
