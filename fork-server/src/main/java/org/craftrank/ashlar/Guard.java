package org.craftrank.ashlar;

import io.papermc.paper.event.player.AsyncChatEvent;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;

/** Anti-bot join limits, name check, command/chat spam limit and hiding the plugin list. */
final class Guard implements Listener {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9_]{3,16}$");

    private final AshlarConfig cfg;
    private final Plugin plugin;

    // anti-bot
    private final Deque<Long> newJoins = new ArrayDeque<>();
    private final Map<InetAddress, Deque<Long>> ipJoins = new ConcurrentHashMap<>();
    private volatile long lockUntil;
    final AtomicInteger blockedJoins = new AtomicInteger();

    // spam
    private final Map<UUID, Rate> cmdRate = new ConcurrentHashMap<>();
    private final Map<UUID, Rate> chatRate = new ConcurrentHashMap<>();
    final AtomicInteger blockedSpam = new AtomicInteger();

    Guard(AshlarConfig cfg, Plugin plugin) {
        this.cfg = cfg;
        this.plugin = plugin;
    }

    boolean lockedDown() {
        return System.currentTimeMillis() < this.lockUntil;
    }

    void endLockdown() {
        this.lockUntil = 0;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        if (this.cfg.nameCheck && !NAME.matcher(e.getName()).matches()) {
            deny(e, "Invalid player name.");
            return;
        }
        if (!this.cfg.antiBot) return;
        long now = System.currentTimeMillis();

        Deque<Long> ip = this.ipJoins.computeIfAbsent(e.getAddress(), k -> new ArrayDeque<>());
        synchronized (ip) {
            while (!ip.isEmpty() && now - ip.peekFirst() > 60_000) ip.pollFirst();
            if (ip.size() >= this.cfg.antiBotPerIpPerMinute) {
                deny(e, "Too many connections from your address. Wait a minute and try again.");
                return;
            }
            ip.addLast(now);
        }

        boolean known = Bukkit.getOfflinePlayer(e.getUniqueId()).hasPlayedBefore();
        if (known) return;
        synchronized (this.newJoins) {
            while (!this.newJoins.isEmpty() && now - this.newJoins.peekFirst() > 1000) this.newJoins.pollFirst();
            this.newJoins.addLast(now);
            if (this.newJoins.size() > this.cfg.antiBotJoinsPerSecond && !lockedDown()) {
                this.lockUntil = now + this.cfg.antiBotLockMinutes * 60_000L;
                Ashlar.LOG.warn("Bot attack suspected ({} new players in 1s). New players are paused for {} minutes; known players can still join.",
                    this.newJoins.size(), this.cfg.antiBotLockMinutes);
            }
        }
        if (lockedDown()) {
            deny(e, "The server is under a bot attack. New players can join again in a few minutes.");
        }
        if (this.ipJoins.size() > 10_000) this.ipJoins.clear();
    }

    private void deny(AsyncPlayerPreLoginEvent e, String msg) {
        this.blockedJoins.incrementAndGet();
        e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text(msg, NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (this.cfg.hidePlugins && !p.hasPermission("ashlar.seeplugins")) {
            String label = e.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
            if (this.cfg.hiddenCommands.contains(label)) {
                e.setCancelled(true);
                p.sendMessage(Component.text("Unknown command. Type \"/help\" for help.", NamedTextColor.RED));
                return;
            }
        }
        if (this.cfg.cmdLimit && !p.hasPermission("ashlar.bypass.spam")
            && !rate(this.cmdRate, p.getUniqueId(), this.cfg.cmdPerSecond)) {
            e.setCancelled(true);
            spam(p, "commands");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent e) {
        Player p = e.getPlayer();
        if (this.cfg.cmdLimit && !p.hasPermission("ashlar.bypass.spam")
            && !rate(this.chatRate, p.getUniqueId(), this.cfg.chatPerSecond)) {
            e.setCancelled(true);
            spam(p, "messages");
        }
    }

    @EventHandler
    public void onCommandList(PlayerCommandSendEvent e) {
        if (this.cfg.hidePlugins && !e.getPlayer().hasPermission("ashlar.seeplugins")) {
            e.getCommands().removeIf(c -> this.cfg.hiddenCommands.contains(c.toLowerCase(Locale.ROOT)));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        this.cmdRate.remove(e.getPlayer().getUniqueId());
        this.chatRate.remove(e.getPlayer().getUniqueId());
    }

    private void spam(Player p, String what) {
        this.blockedSpam.incrementAndGet();
        Rate r = this.cmdRate.computeIfAbsent(p.getUniqueId(), k -> new Rate());
        int n = r.blocked.incrementAndGet();
        if (n >= this.cfg.spamKickAfter) {
            r.blocked.set(0);
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (p.isOnline()) p.kick(Component.text("Kicked for spamming.", NamedTextColor.RED));
            });
        } else {
            p.sendMessage(Component.text("Slow down, you are sending " + what + " too fast.", NamedTextColor.RED));
        }
    }

    /** true when the action is allowed (fewer than max in the last second). */
    private static boolean rate(Map<UUID, Rate> map, UUID id, int max) {
        Rate r = map.computeIfAbsent(id, k -> new Rate());
        long now = System.currentTimeMillis();
        synchronized (r) {
            if (now - r.window >= 1000) {
                r.window = now;
                r.count = 0;
            }
            return ++r.count <= max;
        }
    }

    private static final class Rate {
        long window;
        int count;
        final AtomicInteger blocked = new AtomicInteger();
    }
}
