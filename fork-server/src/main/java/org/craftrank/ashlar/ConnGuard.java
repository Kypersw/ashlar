package org.craftrank.ashlar;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Connection flood protection. It works on each new TCP connection before Minecraft reads a byte, so a
 * flood costs the server almost nothing: banned addresses are dropped at once, every address has a limit on
 * new and open connections, a connection must speak within a few seconds and may not send more than a set
 * amount before it has joined. When the whole server gets more new connections than it should, only
 * addresses that have played here before get in until it calms down.
 * Floods that fill the network line itself can only be stopped by the host's network; nothing on the
 * server can do that.
 */
public final class ConnGuard implements Listener {

    private static volatile ConnGuard instance;
    private final AshlarConfig cfg;
    private final Bans bans;
    private final Map<InetAddress, long[]> perIp = new ConcurrentHashMap<>(); // [second, count]
    private final Map<InetAddress, AtomicInteger> open = new ConcurrentHashMap<>();
    private final Set<String> known = ConcurrentHashMap.newKeySet();
    private final Path knownFile = Path.of("ashlar-known-players.dat");
    private final byte[] salt;
    private final AtomicLong second = new AtomicLong();
    private final AtomicInteger thisSecond = new AtomicInteger();
    private volatile long attackUntil;
    private volatile boolean proxied;
    final AtomicInteger dropped = new AtomicInteger();
    final AtomicInteger droppedAttack = new AtomicInteger();
    private boolean knownDirty;

    ConnGuard(AshlarConfig cfg, Bans bans) {
        this.cfg = cfg;
        this.bans = bans;
        byte[] s = new byte[16];
        try {
            if (Files.isRegularFile(this.knownFile)) {
                var lines = Files.readAllLines(this.knownFile, StandardCharsets.UTF_8);
                if (!lines.isEmpty()) {
                    s = HexFormat.of().parseHex(lines.get(0).trim());
                    for (int i = 1; i < lines.size(); i++) this.known.add(lines.get(i).trim());
                }
            }
        } catch (Exception e) {
            this.known.clear();
        }
        if (this.known.isEmpty() && allZero(s)) new java.security.SecureRandom().nextBytes(s);
        this.salt = s;
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    void start() {
        // behind BungeeCord or Velocity every connection comes from the proxy, so per-address limits would block everyone
        var proxies = io.papermc.paper.configuration.GlobalConfiguration.get().proxies;
        this.proxied = Bukkit.spigot().getConfig().getBoolean("settings.bungeecord", false) || proxies.velocity.enabled || proxies.proxyProtocol;
        instance = this;
    }

    void stop() {
        instance = null;
        saveKnown();
    }

    /** Called by the server for every new connection, before Minecraft sets anything up for it. */
    public static boolean allow(Channel ch) {
        ConnGuard g = instance;
        try {
            return g == null || g.check(ch);
        } catch (RuntimeException e) {
            return true;
        }
    }

    void endAttack() {
        this.attackUntil = 0;
    }

    boolean attack() {
        return System.currentTimeMillis() < this.attackUntil;
    }

    String state() {
        if (!this.cfg.connGuard) return "off";
        if (this.proxied) return "off (behind a proxy; protect the proxy instead)";
        return (attack() ? "UNDER ATTACK (only known players get in)" : "on") + ", dropped " + this.dropped.get()
            + (this.droppedAttack.get() > 0 ? " (" + this.droppedAttack.get() + " during attacks)" : "");
    }

    private String hash(InetAddress a) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(this.salt);
            md.update(a.getAddress());
            return HexFormat.of().formatHex(md.digest(), 0, 12);
        } catch (Exception e) {
            return "";
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        InetSocketAddress a = e.getPlayer().getAddress();
        if (a != null && this.known.size() < 200_000 && this.known.add(hash(a.getAddress()))) this.knownDirty = true;
    }

    void saveKnown() {
        if (!this.knownDirty) return;
        this.knownDirty = false;
        try {
            StringBuilder b = new StringBuilder(HexFormat.of().formatHex(this.salt)).append('\n');
            for (String k : this.known) b.append(k).append('\n');
            Files.writeString(this.knownFile, b.toString());
        } catch (IOException e) {
            Ashlar.LOG.warn("Could not save {}: {}", this.knownFile, e.getMessage());
        }
    }

    private boolean check(Channel ch) {
        if (!this.cfg.connGuard || this.proxied) return true;
        if (!(ch.remoteAddress() instanceof InetSocketAddress isa) || isa.getAddress() == null) return true; // Geyser and other local connections
        InetAddress ip = isa.getAddress();
        if (ip.isLoopbackAddress()) return true;
        long now = System.currentTimeMillis();

        // the whole server: too many new connections in one second means an attack
        long sec = now / 1000;
        if (this.second.getAndSet(sec) != sec) this.thisSecond.set(0);
        if (this.thisSecond.incrementAndGet() > this.cfg.connAttackPerSecond && !attack()) {
            this.attackUntil = now + this.cfg.connAttackMinutes * 60_000L;
            Ashlar.LOG.warn("Connection flood: more than {} new connections in one second. For {} minute(s) only players who joined before can connect.",
                this.cfg.connAttackPerSecond, this.cfg.connAttackMinutes);
        }

        String why = null;
        if (this.bans.isBanned(ip.getHostAddress())) {
            why = "banned";
        } else {
            long[] r = this.perIp.computeIfAbsent(ip, k -> new long[2]);
            synchronized (r) {
                if (r[0] != sec) {
                    r[0] = sec;
                    r[1] = 0;
                }
                if (++r[1] > this.cfg.connPerIpPerSecond) why = "rate";
            }
            if (why == null && attack() && !this.known.contains(hash(ip))) why = "attack";
        }
        AtomicInteger o = this.open.computeIfAbsent(ip, k -> new AtomicInteger());
        if (why == null && o.get() >= this.cfg.connOpenPerIp) why = "open";
        if (why != null) {
            this.dropped.incrementAndGet();
            if (why.equals("attack")) this.droppedAttack.incrementAndGet();
            if (why.equals("rate") || why.equals("open")) this.bans.strike(ip, "connection flood");
            if (this.perIp.size() > 50_000) this.perIp.clear();
            return false;
        }
        o.incrementAndGet();
        ch.closeFuture().addListener(f -> {
            if (o.decrementAndGet() <= 0) this.open.remove(ip, o);
        });
        ch.pipeline().addFirst("ashlar_guard", new Limiter(ip));
        return true;
    }

    /** Closes a connection that stays silent or sends too much before its player has joined. */
    private final class Limiter extends ChannelInboundHandlerAdapter {
        private final InetAddress ip;
        private final long born = System.currentTimeMillis();
        private long windowStart = this.born;
        private long bytes;
        private boolean spoke;

        Limiter(InetAddress ip) {
            this.ip = ip;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            int quiet = ConnGuard.this.cfg.connFirstPacketSeconds;
            ctx.executor().schedule(() -> {
                if (!this.spoke && ctx.channel().isOpen()) {
                    ConnGuard.this.dropped.incrementAndGet();
                    ctx.close();
                }
            }, quiet, TimeUnit.SECONDS);
            // once a player is in, Paper's own packet limiter takes over
            ctx.executor().schedule(() -> {
                if (ctx.pipeline().get("ashlar_guard") == this) ctx.pipeline().remove(this);
            }, 30, TimeUnit.SECONDS);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            this.spoke = true;
            if (msg instanceof ByteBuf b) {
                long now = System.currentTimeMillis();
                if (now - this.windowStart >= 1000) {
                    this.windowStart = now;
                    this.bytes = 0;
                }
                this.bytes += b.readableBytes();
                if (this.bytes > ConnGuard.this.cfg.connMaxBytesPerSecond) {
                    b.release();
                    ConnGuard.this.dropped.incrementAndGet();
                    ConnGuard.this.bans.strike(this.ip, "too much data before joining");
                    ctx.channel().config().setAutoRead(false);
                    ctx.close();
                    return;
                }
            }
            ctx.fireChannelRead(msg);
        }
    }
}
