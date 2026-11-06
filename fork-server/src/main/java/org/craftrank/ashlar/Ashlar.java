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

    private Ashlar(Plugin plugin) {
        this.plugin = plugin;
        this.cfg = new AshlarConfig(new File("ashlar.yml"));
        this.cfg.load();
        this.distance = new DynamicDistance(this.cfg);
        this.guard = new Guard(this.cfg, plugin);
        this.votes = new AshlarVotes(this.cfg, plugin);
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
            r.distance.start(plugin);
            r.votes.start();
            Bukkit.getCommandMap().register("ashlar", new AshlarCommand(r));
            LOG.info("Ready: dynamic distance {}, anti-bot {}, spam limit {}, votes {}",
                onOff(r.cfg.dynDistance), onOff(r.cfg.antiBot), onOff(r.cfg.cmdLimit), r.votes.state);
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
        out.info("Multi-version {}   Anti-bot {}   Spam limit {}   Votes {}", st.via, st.antiBot, st.spam, st.votes);
        out.info("Settings: ashlar.yml   Commands: /ashlar, /ashlar lag, /ashlar preset <name>");
        out.info("Website: https://craftrank.org/ashlar   Source: https://github.com/Kypersw/ashlar");
        out.info(line);
    }

    private record StartState(String via, String antiBot, String spam, String votes) {
        static StartState read(@org.jspecify.annotations.Nullable Ashlar a) {
            String via = Bukkit.getPluginManager().isPluginEnabled("ViaVersion") ? "on (1.8 to 26.x)" : "off";
            if (a == null) return new StartState(via, "?", "?", "?");
            return new StartState(via, onOff(a.cfg.antiBot), onOff(a.cfg.cmdLimit), a.cfg.votes ? "on, port " + a.cfg.votePort : "off");
        }
    }

    public static synchronized void shutdown() {
        if (instance == null) return;
        instance.distance.stop();
        instance.votes.stop();
    }

    private static String onOff(boolean b) {
        return b ? "on" : "off";
    }

    void reload() {
        this.cfg.load();
        this.distance.start(this.plugin);
        this.votes.start();
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
            super("ashlar", "Ashlar status and tools", "/ashlar [status|lag|reload|preset <name>|unlock|token]", List.of("ash"));
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
                    s.sendMessage(Component.text("Anti-bot lockdown ended, new players can join.", NamedTextColor.GREEN));
                }
                case "token" -> {
                    if (!(s instanceof ConsoleCommandSender)) {
                        s.sendMessage(Component.text("For safety the vote token is only shown in the console.", NamedTextColor.RED));
                    } else {
                        s.sendMessage(Component.text("Vote token: " + this.r.cfg.voteToken + "  port: " + this.r.cfg.votePort, NamedTextColor.GRAY));
                    }
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
            if (args.length == 1) return filter(List.of("status", "lag", "reload", "preset", "unlock", "token"), args[0]);
            if (args.length == 2 && args[0].equalsIgnoreCase("preset")) return filter(Presets.NAMES, args[1]);
            return List.of();
        }

        private static List<String> filter(List<String> all, String start) {
            return all.stream().filter(x -> x.startsWith(start.toLowerCase(Locale.ROOT))).toList();
        }
    }
}
