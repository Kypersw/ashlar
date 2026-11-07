package org.craftrank.ashlar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entry point of the Ashlar extras. Started once by the server after plugins are enabled. */
@NullMarked
public final class Ashlar {

    public static final String BRAND = "Ashlar";
    static final Logger LOG = LoggerFactory.getLogger(BRAND);

    private static Ashlar instance;

    private final Plugin plugin;
    private final AshlarConfig cfg;
    private final DynamicDistance distance;
    private final Guard guard;
    private final AshlarVotes votes;
    private final Pregen pregen;
    private final ExploitGuard exploits;
    private final Bans bans;
    private final Auth auth;
    final Backup backup;
    private final WebPanel panel;
    private final Updater updater;
    private final ConnGuard conns;
    private String panelKey = "";

    static @org.jspecify.annotations.Nullable Plugin plugin() {
        return instance == null ? null : instance.plugin;
    }

    private Ashlar(Plugin plugin) {
        this.plugin = plugin;
        this.cfg = new AshlarConfig(new File("ashlar.yml"));
        this.cfg.load();
        this.distance = new DynamicDistance(this.cfg);
        this.guard = new Guard(this.cfg, plugin);
        this.votes = new AshlarVotes(this.cfg, plugin);
        this.pregen = new Pregen(this.cfg);
        this.exploits = new ExploitGuard(this.cfg);
        this.bans = new Bans(this.cfg);
        this.guard.bans = this.bans;
        this.auth = new Auth(this.cfg, plugin, this.bans);
        this.backup = new Backup(this.cfg, plugin);
        this.panel = new WebPanel(this, this.cfg, plugin);
        this.updater = new Updater(this.cfg, plugin);
        this.conns = new ConnGuard(this.cfg, this.bans);
    }

    /** Called from the server once, after the worlds are loaded. */
    public static synchronized void start() {
        if (instance != null) return;
        try {
            Plugin plugin = new AshlarInternal();
            Ashlar r = new Ashlar(plugin);
            instance = r;
            Bukkit.getPluginManager().registerEvents(r.guard, plugin);
            Bukkit.getPluginManager().registerEvents(r.votes, plugin);
            Bukkit.getPluginManager().registerEvents(r.bans, plugin);
            Bukkit.getPluginManager().registerEvents(r.exploits, plugin);
            Bukkit.getPluginManager().registerEvents(r.auth, plugin);
            Bukkit.getPluginManager().registerEvents(r.conns, plugin);
            PluginGuard.configure(r.cfg);
            r.conns.start();
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, r.conns::saveKnown, 6000L, 6000L);
            r.distance.start(plugin);
            r.votes.start();
            r.auth.refresh();
            for (Command c : r.auth.commands()) Bukkit.getCommandMap().register("ashlar", c);
            r.pregen.start(plugin);
            r.backup.start();
            r.startPanel();
            r.updater.start();
            Bukkit.getCommandMap().register("ashlar", new AshlarCommand(r));
            LOG.info("Ready: dynamic distance {}, anti-bot {}, spam limit {}, votes {}, login {}, backups {}, pregen {}",
                onOff(r.cfg.dynDistance), onOff(r.cfg.antiBot), onOff(r.cfg.cmdLimit), r.votes.state, r.loginState(),
                r.cfg.backups ? "every " + r.cfg.backupHours + "h" : "off", onOff(r.cfg.pregen));
        } catch (Throwable t) {
            LOG.error("Extras failed to start, the server keeps running without them", t);
        }
    }

    /** The box printed when the server is ready, with tips on the first start. Same logger as the vanilla start lines, so no prefix. */
    public static void banner(boolean firstStart) {
        Logger out = LoggerFactory.getLogger("net.minecraft.server.MinecraftServer");
        String line = "*".repeat(85);
        out.info(line);
        if (firstStart) {
            out.info("This is the first time you're starting this server. Welcome to Ashlar!");
            out.info("Tip: /ashlar preset survival (or smp, skyblock, minigames) writes tuned settings, then restart.");
            out.info("Paper's getting started guide still applies: https://docs.papermc.io/paper/next-steps");
            out.info("");
        }
        out.info("Ashlar for Minecraft {} is running. Paper plugins work as they are.", Bukkit.getMinecraftVersion());
        StartState st = StartState.read(instance);
        out.info("Multi-version {}   Bedrock {}   Anti-bot {}   Spam limit {}   Votes {}", st.via, st.bedrock, st.antiBot, st.spam, st.votes);
        if (instance != null) {
            out.info("Login {}   Backups {}   Web panel {}", instance.loginState(),
                instance.cfg.backups ? "every " + instance.cfg.backupHours + "h" : "off", instance.panel.state);
        }
        out.info("Settings: ashlar.yml   Commands: /ashlar, /ashlar lag, /ashlar preset <name>");
        out.info("Website: https://craftrank.org/ashlar   Source: https://github.com/Kypersw/ashlar");
        out.info(line);
    }

    private record StartState(String via, String bedrock, String antiBot, String spam, String votes) {
        static StartState read(@org.jspecify.annotations.Nullable Ashlar a) {
            String via = Bukkit.getPluginManager().isPluginEnabled("ViaVersion") ? "on (1.8 to 26.x)" : "off";
            String bedrock = Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot") ? "on (UDP 19132)" : "off";
            if (a == null) return new StartState(via, bedrock, "?", "?", "?");
            return new StartState(via, bedrock, onOff(a.cfg.antiBot), onOff(a.cfg.cmdLimit), a.cfg.votes ? "on, port " + a.cfg.votePort : "off");
        }
    }

    public static synchronized void shutdown() {
        if (instance == null) return;
        instance.distance.stop();
        instance.votes.stop();
        instance.pregen.stop();
        instance.backup.stop();
        instance.updater.stop();
        instance.panel.stop();
        instance.conns.stop();
        instance.bans.write();
    }

    /** What the server logs for a command: login passwords are replaced with stars. */
    public static String forLog(String command) {
        String c = command.stripLeading();
        boolean slash = c.startsWith("/");
        String body = slash ? c.substring(1) : c;
        int sp = body.indexOf(' ');
        if (sp < 0) return command;
        String label = body.substring(0, sp).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);
        if (!Auth.COMMANDS.contains(label)) return command;
        return (slash ? "/" : "") + body.substring(0, sp) + " ****";
    }

    String loginState() {
        if (this.auth.other != null) return "off (" + this.auth.other + " is installed)";
        return this.auth.active ? "on" : "off";
    }

    private void startPanel() {
        String key = this.cfg.panel + "|" + this.cfg.panelHost + "|" + this.cfg.panelPort;
        if (key.equals(this.panelKey)) return;
        this.panelKey = key;
        this.panel.start();
    }

    boolean getSwitch(String key) {
        return this.cfg.flag(key);
    }

    void setSwitch(String key, boolean on) {
        this.cfg.write(key, on);
        reload();
        LOG.info("Web panel turned {} {}", key, onOff(on));
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    void reload() {
        this.cfg.load();
        this.distance.start(this.plugin);
        this.votes.start();
        this.auth.refresh();
        PluginGuard.configure(this.cfg);
        this.pregen.start(this.plugin);
        this.backup.start();
        this.updater.start();
        startPanel();
    }

    List<String> status() {
        List<String> l = new ArrayList<>();
        double[] tps = Bukkit.getTPS();
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20;
        l.add(String.format(Locale.ROOT, "TPS %.1f / %.1f / %.1f   MSPT %.1f", tps[0], tps[1], tps[2], Bukkit.getAverageTickTime()));
        l.add(String.format(Locale.ROOT, "Players %d   Memory %d / %d MB", Bukkit.getOnlinePlayers().size(), used, rt.maxMemory() >> 20));
        for (World w : Bukkit.getWorlds()) {
            l.add(String.format(Locale.ROOT, "%s: %d chunks, %d entities, view %d, simulation %d",
                w.getName(), w.getLoadedChunks().length, w.getEntityCount(), w.getViewDistance(), w.getSimulationDistance()));
        }
        l.add("Dynamic distance: " + this.distance.last);
        l.add("Anti-bot: " + (this.cfg.antiBot ? (this.guard.lockedDown() ? "LOCKDOWN (new players paused)" : "on") : "off")
            + ", blocked joins " + this.guard.blockedJoins.get() + ", blocked spam " + this.guard.blockedSpam.get());
        l.add("Votes: " + this.votes.state + ", received " + this.votes.received.get() + ", rejected " + this.votes.rejected.get()
            + ", waiting for offline players " + this.votes.pendingCount());
        l.add("Exploit guard: " + onOff(this.cfg.exploits) + ", blocked " + this.exploits.blocked.get());
        l.add("IP bans: " + this.bans.count() + " active, blocked joins " + this.bans.blocked.get()
            + (this.cfg.vpnCheck ? ", VPN check on, blocked " + this.bans.vpnBlocked.get() : ", VPN check off"));
        l.add("Connection guard: " + this.conns.state());
        l.add("Plugin guard: " + PluginGuard.status());
        l.add("Login: " + loginState() + (this.auth.active ? ", " + this.auth.accounts() + " accounts" : ""));
        l.add("Pregen: " + this.pregen.last);
        l.add("Backups: " + (this.cfg.backups ? "every " + this.cfg.backupHours + "h, last " + this.backup.last : "off"));
        l.add("Web panel: " + this.panel.state);
        l.add("Updates: " + this.updater.last);
        return l;
    }

    /** Where the load comes from: busiest chunks and the most common entity types. */
    static List<String> lag() {
        List<String> l = new ArrayList<>();
        java.util.Map<String, Integer> types = new java.util.HashMap<>();
        List<String[]> chunks = new ArrayList<>();
        for (World w : Bukkit.getWorlds()) {
            for (org.bukkit.Chunk c : w.getLoadedChunks()) {
                org.bukkit.entity.Entity[] es = c.getEntities();
                int tiles = c.getTileEntities(false).length;
                for (org.bukkit.entity.Entity e : es) types.merge(e.getType().name().toLowerCase(Locale.ROOT), 1, Integer::sum);
                if (es.length + tiles > 0) {
                    chunks.add(new String[] {String.valueOf(es.length + tiles),
                        String.format(Locale.ROOT, "%s %d,%d (block %d,%d): %d entities, %d block entities",
                            w.getName(), c.getX(), c.getZ(), c.getX() << 4, c.getZ() << 4, es.length, tiles)});
                }
            }
        }
        l.add("Busiest chunks:");
        chunks.stream().sorted((a, b) -> Integer.parseInt(b[0]) - Integer.parseInt(a[0])).limit(5).forEach(c -> l.add("  " + c[1]));
        l.add("Most common entities:");
        types.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6)
            .forEach(e -> l.add("  " + e.getKey() + ": " + e.getValue()));
        if (chunks.isEmpty()) l.add("  nothing loaded");
        return l;
    }

    @NullMarked
    static final class AshlarCommand extends Command {
        private final Ashlar r;

        AshlarCommand(Ashlar r) {
            super("ashlar", "Ashlar status and tools", "/ashlar [status|lag|reload|preset|unlock|token|pregen|backup|bans|ban-ip|unban-ip|unregister|panel|update|plugins]", List.of("ash"));
            this.setPermission("ashlar.admin");
            this.r = r;
        }

        @Override
        public boolean execute(CommandSender s, String label, String[] args) {
            if (!s.hasPermission("ashlar.admin")) {
                s.sendMessage(Component.text("You do not have permission.", NamedTextColor.RED));
                return true;
            }
            String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "status" -> {
                    s.sendMessage(Component.text("Ashlar", NamedTextColor.GOLD));
                    for (String line : this.r.status()) s.sendMessage(Component.text(" " + line, NamedTextColor.GRAY));
                }
                case "lag" -> {
                    s.sendMessage(Component.text("Ashlar lag report", NamedTextColor.GOLD));
                    for (String line : lag()) s.sendMessage(Component.text(" " + line, NamedTextColor.GRAY));
                }
                case "reload" -> {
                    this.r.reload();
                    s.sendMessage(Component.text("ashlar.yml reloaded.", NamedTextColor.GREEN));
                }
                case "unlock" -> {
                    this.r.guard.endLockdown();
                    this.r.conns.endAttack();
                    s.sendMessage(Component.text("Anti-bot lockdown ended, new players can join.", NamedTextColor.GREEN));
                }
                case "token" -> {
                    if (!(s instanceof ConsoleCommandSender)) {
                        s.sendMessage(Component.text("For safety the vote token is only shown in the console.", NamedTextColor.RED));
                    } else {
                        s.sendMessage(Component.text("Vote token: " + this.r.cfg.voteToken + "  port: " + this.r.cfg.votePort, NamedTextColor.GRAY));
                    }
                }
                case "pregen" -> {
                    String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                    switch (what) {
                        case "pause" -> this.r.pregen.setPaused(true);
                        case "resume" -> this.r.pregen.setPaused(false);
                        case "reset" -> this.r.pregen.reset();
                        default -> { }
                    }
                    s.sendMessage(Component.text("Pregen: " + this.r.pregen.last
                        + (what.isEmpty() ? "  (/ashlar pregen pause|resume|reset)" : ""), NamedTextColor.GRAY));
                }
                case "backup" -> {
                    if (args.length > 1 && args[1].equalsIgnoreCase("list")) {
                        List<File> l = this.r.backup.list();
                        if (l.isEmpty()) s.sendMessage(Component.text("No backups yet.", NamedTextColor.GRAY));
                        for (File f : l) s.sendMessage(Component.text(" " + f.getName() + "  " + (f.length() >> 20) + " MB", NamedTextColor.GRAY));
                        return true;
                    }
                    s.sendMessage(Component.text("Backing up the worlds...", NamedTextColor.GRAY));
                    this.r.backup.run(msg -> s.sendMessage(Component.text(msg, msg.startsWith("Backup saved") ? NamedTextColor.GREEN : NamedTextColor.RED)));
                }
                case "bans" -> {
                    List<String> l = this.r.bans.list();
                    if (l.isEmpty()) s.sendMessage(Component.text("No address is banned.", NamedTextColor.GRAY));
                    for (String b : l) s.sendMessage(Component.text(" " + b, NamedTextColor.GRAY));
                }
                case "ban-ip", "unban-ip" -> {
                    if (args.length < 2 || !(args[1].matches("\\d{1,3}(\\.\\d{1,3}){3}") || args[1].matches("[0-9A-Fa-f:.]{2,45}") && args[1].contains(":"))) {
                        s.sendMessage(Component.text("Usage: /ashlar " + sub + " <ip>" + (sub.equals("ban-ip") ? " [minutes]" : ""), NamedTextColor.RED));
                        return true;
                    }
                    String ip;
                    try {
                        ip = java.net.InetAddress.getByName(args[1]).getHostAddress();
                    } catch (Exception e) {
                        s.sendMessage(Component.text("That is not an IP address.", NamedTextColor.RED));
                        return true;
                    }
                    if (sub.equals("unban-ip")) {
                        s.sendMessage(Component.text(this.r.bans.unban(ip) ? ip + " unbanned." : ip + " was not banned.", NamedTextColor.GREEN));
                    } else {
                        int min = 60 * 24 * 365 * 10;
                        if (args.length > 2) {
                            try {
                                min = Math.max(1, Integer.parseInt(args[2]));
                            } catch (NumberFormatException e) {
                                s.sendMessage(Component.text("Minutes must be a number.", NamedTextColor.RED));
                                return true;
                            }
                        }
                        this.r.bans.ban(ip, min, "by " + s.getName());
                        for (org.bukkit.entity.Player p : Bukkit.getOnlinePlayers()) {
                            if (p.getAddress() != null && p.getAddress().getAddress().getHostAddress().equals(ip)) p.kick(Component.text("Your address is banned.", NamedTextColor.RED));
                        }
                        s.sendMessage(Component.text(ip + " banned" + (args.length > 2 ? " for " + min + " minutes." : "."), NamedTextColor.GREEN));
                    }
                }
                case "unregister" -> {
                    if (args.length < 2) {
                        s.sendMessage(Component.text("Usage: /ashlar unregister <player>", NamedTextColor.RED));
                        return true;
                    }
                    s.sendMessage(Component.text(this.r.auth.unregister(args[1]) ? args[1] + " can register again." : args[1] + " has no account.", NamedTextColor.GREEN));
                }
                case "panel" -> {
                    if (!(s instanceof ConsoleCommandSender)) {
                        s.sendMessage(Component.text("For safety the panel token is only shown in the console.", NamedTextColor.RED));
                    } else {
                        s.sendMessage(Component.text("Web panel: " + this.r.panel.state + "  token: " + this.r.cfg.panelToken, NamedTextColor.GRAY));
                    }
                }
                case "plugins" -> {
                    s.sendMessage(Component.text("Ashlar plugin guard", NamedTextColor.GOLD));
                    for (String line : PluginGuard.report()) s.sendMessage(Component.text(" " + line, NamedTextColor.GRAY));
                }
                case "update" -> {
                    this.r.updater.checkNow();
                    s.sendMessage(Component.text("Checking GitHub for a newer build. Last result: " + this.r.updater.last, NamedTextColor.GRAY));
                }
                case "preset" -> {
                    if (args.length < 2 || !Presets.NAMES.contains(args[1].toLowerCase(Locale.ROOT))) {
                        s.sendMessage(Component.text("Usage: /ashlar preset <" + String.join("|", Presets.NAMES) + ">", NamedTextColor.RED));
                        return true;
                    }
                    try {
                        for (String line : Presets.apply(new File("."), args[1])) s.sendMessage(Component.text(" " + line, NamedTextColor.GRAY));
                        s.sendMessage(Component.text("Preset " + args[1] + " saved. Restart the server to use it.", NamedTextColor.GREEN));
                    } catch (Exception e) {
                        s.sendMessage(Component.text("Could not apply the preset: " + e.getMessage(), NamedTextColor.RED));
                    }
                }
                default -> s.sendMessage(Component.text(this.getUsage(), NamedTextColor.RED));
            }
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender s, String alias, String[] args) {
            if (!s.hasPermission("ashlar.admin")) return List.of();
            if (args.length == 1) return filter(List.of("status", "lag", "reload", "preset", "unlock", "token", "pregen", "backup", "bans", "ban-ip", "unban-ip", "unregister", "panel", "update", "plugins"), args[0]);
            if (args.length == 2 && args[0].equalsIgnoreCase("preset")) return filter(Presets.NAMES, args[1]);
            if (args.length == 2 && args[0].equalsIgnoreCase("pregen")) return filter(List.of("pause", "resume", "reset"), args[1]);
            if (args.length == 2 && args[0].equalsIgnoreCase("backup")) return filter(List.of("list"), args[1]);
            return List.of();
        }

        private static List<String> filter(List<String> all, String start) {
            return all.stream().filter(x -> x.startsWith(start.toLowerCase(Locale.ROOT))).toList();
        }
    }
}
