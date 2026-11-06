package org.craftrank.ashlar;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Built-in vote receiver that speaks the Votifier v2 protocol (token + HMAC), so vote sites
 * such as CraftRank can reward players without installing NuVotifier.
 * Leave it off if you already run NuVotifier on the same port.
 */
final class AshlarVotes implements org.bukkit.event.Listener {

    private static final Pattern USER = Pattern.compile("^[.*]?[A-Za-z0-9_]{1,16}$");
    private static final SecureRandom RNG = new SecureRandom();

    private final AshlarConfig cfg;
    private final Plugin plugin;
    private ServerSocket socket;
    private ExecutorService pool;
    private final Semaphore slots = new Semaphore(16);
    final AtomicInteger received = new AtomicInteger();
    final AtomicInteger rejected = new AtomicInteger();
    volatile String state = "off";

    AshlarVotes(AshlarConfig cfg, Plugin plugin) {
        this.cfg = cfg;
        this.plugin = plugin;
    }

    static String newToken() {
        byte[] b = new byte[24];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    void start() {
        stop();
        if (!this.cfg.votes) {
            this.state = "off";
            return;
        }
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            // after a reload the old socket can take a moment to let go of the port
            for (int i = 0; ; i++) {
                try {
                    s.bind(new InetSocketAddress(this.cfg.voteHost, this.cfg.votePort), 50);
                    break;
                } catch (java.net.BindException e) {
                    if (i >= 10) throw e;
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
            this.socket = s;
            this.pool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "Ashlar Votes");
                t.setDaemon(true);
                return t;
            });
            Thread accept = new Thread(this::acceptLoop, "Ashlar Votes accept");
            accept.setDaemon(true);
            accept.start();
            this.state = "listening on port " + this.cfg.votePort;
            Ashlar.LOG.info("Vote receiver (Votifier v2) {}", this.state);
        } catch (IOException e) {
            this.state = "failed: " + e.getMessage();
            Ashlar.LOG.error("Could not open vote port {}: {}", this.cfg.votePort, e.getMessage());
        }
    }

    void stop() {
        try {
            if (this.socket != null) this.socket.close();
        } catch (IOException ignored) {
        }
        if (this.pool != null) this.pool.shutdownNow();
        this.socket = null;
        this.pool = null;
    }

    private void acceptLoop() {
        ServerSocket s = this.socket;
        while (s != null && !s.isClosed()) {
            try {
                Socket c = s.accept();
                if (!this.slots.tryAcquire()) {
                    c.close();
                    continue;
                }
                this.pool.execute(() -> {
                    try (c) {
                        handle(c);
                    } catch (Exception e) {
                        this.rejected.incrementAndGet();
                    } finally {
                        this.slots.release();
                    }
                });
            } catch (IOException e) {
                if (s.isClosed()) return;
            }
        }
    }

    private void handle(Socket c) throws Exception {
        c.setSoTimeout(5000);
        String challenge = newToken().substring(0, 24);
        OutputStream out = c.getOutputStream();
        out.write(("VOTIFIER 2 " + challenge + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();

        DataInputStream in = new DataInputStream(c.getInputStream());
        if (in.readShort() != 0x733A) throw new IOException("bad magic");
        int len = in.readUnsignedShort();
        if (len <= 0 || len > 8192) throw new IOException("bad length");
        byte[] buf = in.readNBytes(len);
        if (buf.length != len) throw new IOException("short read");

        JsonObject msg = JsonParser.parseString(new String(buf, StandardCharsets.UTF_8)).getAsJsonObject();
        String payload = msg.get("payload").getAsString();
        byte[] sig = Base64.getDecoder().decode(msg.get("signature").getAsString());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(this.cfg.voteToken.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] want = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(want, sig)) {
            reply(out, false, "Signature is not valid (wrong token?)");
            return;
        }
        JsonObject vote = JsonParser.parseString(payload).getAsJsonObject();
        if (!challenge.equals(vote.has("challenge") ? vote.get("challenge").getAsString() : "")) {
            reply(out, false, "Challenge does not match");
            return;
        }
        String user = vote.has("username") ? vote.get("username").getAsString() : "";
        String service = vote.has("serviceName") ? vote.get("serviceName").getAsString() : "a vote site";
        if (!USER.matcher(user).matches()) {
            reply(out, false, "Invalid username");
            return;
        }
        String safeService = service.replaceAll("[^A-Za-z0-9 ._-]", "");
        if (safeService.length() > 40) safeService = safeService.substring(0, 40);
        reply(out, true, null);
        this.received.incrementAndGet();
        reward(user, safeService.isBlank() ? "a vote site" : safeService, c.getInetAddress());
    }

    private final java.io.File pendingFile = new java.io.File("ashlar-votes.yml");
    private final org.bukkit.configuration.file.YamlConfiguration pending =
        org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(this.pendingFile);

    private void give(String user, String service) {
        for (String cmd : this.cfg.voteCommands) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", user).replace("%service%", service));
        }
    }

    private void savePending() {
        try {
            this.pending.save(this.pendingFile);
        } catch (IOException e) {
            Ashlar.LOG.warn("Could not save ashlar-votes.yml: {}", e.getMessage());
        }
    }

    int pendingCount() {
        int n = 0;
        for (String k : this.pending.getKeys(false)) n += this.pending.getStringList(k).size();
        return n;
    }

    @org.bukkit.event.EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        String key = e.getPlayer().getName().toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> list = this.pending.getStringList(key);
        if (list.isEmpty()) return;
        this.pending.set(key, null);
        savePending();
        String name = e.getPlayer().getName();
        // wait a moment so the player is fully in the world before items are given
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            for (String service : list) give(name, service);
            e.getPlayer().sendMessage(net.kyori.adventure.text.Component.text(
                "You got the rewards for " + list.size() + " vote" + (list.size() == 1 ? "" : "s") + " while you were away.",
                net.kyori.adventure.text.format.NamedTextColor.GREEN));
        }, 40L);
    }

    private void reply(OutputStream out, boolean ok, String cause) throws IOException {
        JsonObject r = new JsonObject();
        r.addProperty("status", ok ? "ok" : "error");
        if (!ok) {
            r.addProperty("cause", "AshlarVoteError");
            r.addProperty("error", cause);
            this.rejected.incrementAndGet();
        }
        out.write((r + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void reward(String user, String service, InetAddress from) {
        Ashlar.LOG.info("Vote from {} for {} ({})", service, user, from.getHostAddress());
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (Bukkit.getPlayerExact(user) != null || !this.cfg.voteOffline) {
                give(user, service);
            } else {
                // player is offline: keep the reward until they join
                java.util.List<String> list = this.pending.getStringList(user.toLowerCase(java.util.Locale.ROOT));
                if (list.size() < 50) list.add(service);
                this.pending.set(user.toLowerCase(java.util.Locale.ROOT), list);
                savePending();
            }
            if (!this.cfg.voteBroadcast.isBlank()) {
                Bukkit.broadcast(LegacyComponentSerializer.legacyAmpersand().deserialize(
                    this.cfg.voteBroadcast.replace("%player%", user).replace("%service%", service)));
            }
        });
    }
}
