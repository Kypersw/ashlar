package org.craftrank.ashlar;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.NullMarked;

/**
 * /register and /login for offline-mode servers, so nobody can join under someone else's name.
 * Passwords are stored only as PBKDF2-SHA256 hashes with a random salt (ashlar-auth.yml). Until a
 * player is signed in they cannot move, chat, use commands, open inventories or take damage.
 * Bedrock players coming through Floodgate are signed in by Xbox and skip this.
 */
@NullMarked
final class Auth implements Listener {

    static final Set<String> COMMANDS = Set.of("login", "l", "log", "register", "reg", "changepassword", "changepass");
    private static final SecureRandom RNG = new SecureRandom();
    private static final int ITER = 120_000;

    private final AshlarConfig cfg;
    private final Plugin plugin;
    private final Bans bans;
    private final File file = new File("ashlar-auth.yml");
    private final YamlConfiguration db;
    private final Set<UUID> pending = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> tries = new ConcurrentHashMap<>();
    private final Map<UUID, Location> holdAt = new ConcurrentHashMap<>();
    boolean active;
    @org.jspecify.annotations.Nullable String other;

    Auth(AshlarConfig cfg, Plugin plugin, Bans bans) {
        this.cfg = cfg;
        this.plugin = plugin;
        this.bans = bans;
        this.db = YamlConfiguration.loadConfiguration(this.file);
        refresh();
    }

    void refresh() {
        String m = this.cfg.login.toLowerCase(Locale.ROOT);
        boolean on = m.equals("on") || m.equals("true") || (m.equals("auto") && !Bukkit.getOnlineMode());
        // a login plugin the owner installed takes priority
        this.other = java.util.stream.Stream.of("AuthMe", "nLogin", "LibreLogin", "OpenLogin", "JPremium", "LoginSecurity")
            .filter(n -> Bukkit.getPluginManager().getPlugin(n) != null).findFirst().orElse(null);
        this.active = on && this.other == null;
    }

    int accounts() {
        return this.db.getKeys(false).size();
    }

    boolean signedIn(Player p) {
        return !this.pending.contains(p.getUniqueId());
    }

    private static boolean bedrock(Player p) {
        return p.getUniqueId().getMostSignificantBits() == 0 && Bukkit.getPluginManager().isPluginEnabled("floodgate");
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private void save() {
        try {
            this.db.save(this.file);
        } catch (IOException e) {
            Ashlar.LOG.warn("Could not save ashlar-auth.yml: {}", e.getMessage());
        }
    }

    static String hash(String pw) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        return "pbkdf2$" + ITER + "$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(derive(pw, salt, ITER));
    }

    static boolean check(String pw, String stored) {
        String[] p = stored.split("\\$");
        if (p.length != 4 || !p[0].equals("pbkdf2")) return false;
        byte[] want = Base64.getDecoder().decode(p[3]);
        return MessageDigest.isEqual(want, derive(pw, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1])));
    }

    private static byte[] derive(String pw, byte[] salt, int iter) {
        try {
            PBEKeySpec spec = new PBEKeySpec(pw.toCharArray(), salt, iter, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void say(Player p, String s, NamedTextColor c) {
        p.sendMessage(Component.text(s, c));
    }

    // ---------- joining ----------

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent e) {
        if (!this.active || e.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        // one name, one person: a second connection must not kick the signed-in owner of the name
        Player on = Bukkit.getPlayerExact(e.getName());
        if (on != null && signedIn(on)) {
            e.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Component.text("A player with this name is already online.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!this.active || bedrock(p)) return;
        String k = key(p.getName());
        String ip = p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "";
        long last = this.db.getLong(k + ".last", 0);
        if (this.db.contains(k + ".hash") && this.cfg.loginSessionMinutes > 0 && ip.equals(this.db.getString(k + ".ip"))
            && System.currentTimeMillis() - last < this.cfg.loginSessionMinutes * 60_000L) {
            say(p, "Welcome back, you are signed in.", NamedTextColor.GREEN);
            return;
        }
        this.pending.add(p.getUniqueId());
        this.holdAt.put(p.getUniqueId(), p.getLocation());
        boolean has = this.db.contains(k + ".hash");
        UUID id = p.getUniqueId();
        Bukkit.getScheduler().runTaskTimer(this.plugin, t -> {
            Player q = Bukkit.getPlayer(id);
            if (q == null || !this.pending.contains(id)) {
                t.cancel();
                return;
            }
            say(q, has ? "Sign in with /login <password>" : "Choose a password with /register <password> <password>", NamedTextColor.GOLD);
        }, 1L, 100L);
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            Player q = Bukkit.getPlayer(id);
            if (q != null && this.pending.contains(id)) q.kick(Component.text("You did not sign in in time.", NamedTextColor.RED));
        }, this.cfg.loginTimeout * 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        this.pending.remove(e.getPlayer().getUniqueId());
        this.tries.remove(e.getPlayer().getUniqueId());
        this.holdAt.remove(e.getPlayer().getUniqueId());
    }

    // ---------- commands (registered as real commands so they never reach other plugins) ----------

    boolean handle(Player p, String label, String[] a) {
        String k = key(p.getName());
        String ip = p.getAddress() != null ? p.getAddress().getAddress().getHostAddress() : "";
        switch (label) {
            case "register", "reg" -> {
                if (!this.active) return say(p);
                if (this.db.contains(k + ".hash")) {
                    say(p, "This name is already registered. Use /login <password>.", NamedTextColor.RED);
                    return true;
                }
                if (a.length < 2 || !a[0].equals(a[1])) {
                    say(p, "Type the password twice: /register <password> <password>", NamedTextColor.RED);
                    return true;
                }
                if (a[0].length() < this.cfg.loginMinLength || a[0].length() > 64) {
                    say(p, "Use " + this.cfg.loginMinLength + " to 64 characters.", NamedTextColor.RED);
                    return true;
                }
                if (a[0].equalsIgnoreCase(p.getName())) {
                    say(p, "Do not use your name as the password.", NamedTextColor.RED);
                    return true;
                }
                long same = this.db.getKeys(false).stream().filter(n -> ip.equals(this.db.getString(n + ".ip"))).count();
                if (same >= this.cfg.loginMaxPerIp) {
                    say(p, "Too many accounts from your address.", NamedTextColor.RED);
                    return true;
                }
                String h = hash(a[0]);
                this.db.set(k + ".hash", h);
                this.db.set(k + ".ip", ip);
                this.db.set(k + ".last", System.currentTimeMillis());
                save();
                done(p, "Registered and signed in. Do not use this password anywhere else.");
            }
            case "login", "l", "log" -> {
                if (!this.active) return say(p);
                if (signedIn(p)) {
                    say(p, "You are already signed in.", NamedTextColor.GREEN);
                    return true;
                }
                String h = this.db.getString(k + ".hash");
                if (h == null) {
                    say(p, "This name is not registered yet: /register <password> <password>", NamedTextColor.RED);
                    return true;
                }
                if (a.length < 1 || !check(a[0], h)) {
                    int n = this.tries.merge(p.getUniqueId(), 1, Integer::sum);
                    if (p.getAddress() != null) this.bans.strike(p.getAddress().getAddress(), "wrong passwords");
                    if (n >= this.cfg.loginMaxTries) {
                        p.kick(Component.text("Too many wrong passwords.", NamedTextColor.RED));
                    } else {
                        say(p, "Wrong password (" + (this.cfg.loginMaxTries - n) + " tries left).", NamedTextColor.RED);
                    }
                    return true;
                }
                this.db.set(k + ".ip", ip);
                this.db.set(k + ".last", System.currentTimeMillis());
                save();
                done(p, "Signed in.");
            }
            case "changepassword", "changepass" -> {
                if (!this.active) return say(p);
                String h = this.db.getString(k + ".hash");
                if (!signedIn(p) || h == null || a.length < 2 || !check(a[0], h)) {
                    say(p, "Usage: /changepassword <old password> <new password>", NamedTextColor.RED);
                    return true;
                }
                if (a[1].length() < this.cfg.loginMinLength || a[1].length() > 64) {
                    say(p, "Use " + this.cfg.loginMinLength + " to 64 characters.", NamedTextColor.RED);
                    return true;
                }
                this.db.set(k + ".hash", hash(a[1]));
                save();
                say(p, "Password changed.", NamedTextColor.GREEN);
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static boolean say(Player p) {
        say(p, "Logins are off on this server.", NamedTextColor.GRAY);
        return true;
    }

    private void done(Player p, String msg) {
        this.pending.remove(p.getUniqueId());
        this.tries.remove(p.getUniqueId());
        this.holdAt.remove(p.getUniqueId());
        say(p, msg, NamedTextColor.GREEN);
    }

    boolean unregister(String name) {
        String k = key(name);
        if (!this.db.contains(k)) return false;
        this.db.set(k, null);
        save();
        return true;
    }

    List<org.bukkit.command.Command> commands() {
        return List.of(new Cmd(this, "login", List.of("l", "log")), new Cmd(this, "register", List.of("reg")),
            new Cmd(this, "changepassword", List.of("changepass")));
    }

    @NullMarked
    static final class Cmd extends Command {
        private final Auth a;

        Cmd(Auth a, String name, List<String> aliases) {
            super(name, "Ashlar login", "/" + name, aliases);
            this.a = a;
        }

        @Override
        public boolean execute(CommandSender s, String label, String[] args) {
            if (s instanceof Player p) this.a.handle(p, this.getName(), args);
            return true;
        }

        @Override
        public List<String> tabComplete(CommandSender s, String alias, String[] args) {
            return List.of();
        }
    }

    // ---------- holding players who are not signed in ----------

    private boolean held(Player p) {
        return this.active && this.pending.contains(p.getUniqueId());
    }

    private void stop(Player p, Cancellable e) {
        if (held(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent e) {
        if (!held(e.getPlayer())) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getBlockX() != to.getBlockX() || from.getBlockZ() != to.getBlockZ() || to.getY() > from.getY()) {
            Location hold = this.holdAt.getOrDefault(e.getPlayer().getUniqueId(), from);
            Location back = hold.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            e.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        if (!held(e.getPlayer())) return;
        String label = e.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (!COMMANDS.contains(label)) {
            e.setCancelled(true);
            say(e.getPlayer(), "Sign in first.", NamedTextColor.RED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        if (held(e.getPlayer())) {
            e.setCancelled(true);
            say(e.getPlayer(), "Sign in first.", NamedTextColor.RED);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST) public void onInteract(PlayerInteractEvent e) { stop(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onInteractEntity(PlayerInteractEntityEvent e) { stop(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onBreak(BlockBreakEvent e) { stop(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onPlace(BlockPlaceEvent e) { stop(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onDrop(PlayerDropItemEvent e) { stop(e.getPlayer(), e); }
    @EventHandler(priority = EventPriority.LOWEST) public void onSwap(PlayerSwapHandItemsEvent e) { stop(e.getPlayer(), e); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPickup(EntityPickupItemEvent e) {
        if (e.getEntity() instanceof Player p) stop(p, e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvOpen(InventoryOpenEvent e) {
        if (e.getPlayer() instanceof Player p) stop(p, e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) stop(p, e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p) stop(p, e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p) stop(p, e);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFood(FoodLevelChangeEvent e) {
        if (e.getEntity() instanceof Player p) stop(p, e);
    }
}
