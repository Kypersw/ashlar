# Ashlar

**Website and download: https://craftrank.org/ashlar**

A Minecraft server built on Paper, with extra performance, security and quality-of-life features built in.
Paper plugins work as they are.

## Features
- **Every game version can join**: ViaVersion, ViaBackwards and ViaRewind (GPLv3) ship inside the jar and are put into `plugins/` on first start. Tested with clients 1.8.9, 1.12.2, 1.16.5, 1.19.4, 1.20.4, 1.21.4, 1.21.11 and 26.1. Turn off with `multi-version.enabled: false`; a copy you installed yourself is never touched.
- **Dynamic simulation distance**: lowers the simulation distance step by step when MSPT goes high and raises it again when the server has room. View distance stays the same.
- **Anti-bot**: limits joins per IP and new players per second. During an attack new players are paused for a few minutes; players who joined before can still get in.
- **Name check**: blocks invalid player names (useful in offline mode).
- **Spam limit**: caps commands and chat per second, kicks players who keep spamming.
- **Hidden plugin list**: `/plugins`, `/version` and similar are hidden from players without `ashlar.seeplugins`.
- **Built-in vote receiver** (Votifier v2 protocol, token + HMAC): runs reward commands, broadcasts the vote, and keeps rewards for offline players until they join. Off by default; turn it off if you run NuVotifier.
- **Presets**: `/ashlar preset <survival|smp|skyblock|minigames>` writes tuned server.properties, bukkit.yml, spigot.yml and paper-world-defaults.yml (old files are backed up to `ashlar-backup/`). Restart to use them.
- **Lag report**: `/ashlar lag` lists the busiest chunks and the most common entities.
- **Bedrock players**: Geyser and Floodgate (MIT) are downloaded from GeyserMC on first start, checked against their SHA-256 and kept up to date. Bedrock players join on port 19132 and skip the login. Turn off with `bedrock.enabled: false`.
- **Login for offline servers**: `/register` and `/login` when `online-mode=false`. Passwords are stored as PBKDF2-SHA256 hashes, never written to the log, and a player who is not signed in cannot move, chat, use commands or take damage. Steps aside when AuthMe, nLogin, LibreLogin or a similar plugin is installed.
- **Exploit guard**: blocks oversized books and signs, limits tab-complete spam and caps entities and TNT per chunk.
- **Plugin backdoor guard**: a plugin cannot make a player an operator or give out every permission (`op`, `lp ... permission set *` and similar) unless you list it in `security.plugin-guard.allowed-plugins`. This is how most backdoors in leaked plugins work. On start every plugin jar is checked for code that runs programs on the machine, downloads code, or hides a web address (for example in Base64), and the console logs which outside addresses each plugin connects to. `/ashlar plugins` shows the report.
- **Connection flood protection**: every new connection is checked before Minecraft does any work for it. Banned addresses are dropped at once, each address has a limit on new and open connections, silent connections are closed after a few seconds and a connection that sends too much before joining is cut. When the whole server gets too many new connections, only players who joined before get in until it calms down (`/ashlar unlock` ends it early). In our test 16,000 connections in 12 seconds from one address were all dropped while a known player joined. Banned addresses are written to `ashlar-blocked-ips.txt` for a firewall. This does not replace network-level DDoS protection from your host: a flood that fills the line never reaches the server. Turns itself off behind BungeeCord, Velocity or the PROXY protocol.
- **IP bans**: an address that keeps getting blocked is banned for a while (`ashlar-bans.yml`, kept across restarts). Optional VPN/proxy check through proxycheck.io, off by default.
- **Chunk pre-generation**: generates the area around spawn while nobody is online and the server is idle, and pauses when a player joins.
- **Automatic backups**: zips the worlds every few hours into `backups/`, keeps the newest ones, and skips the run when the disk is nearly full.
- **Web panel**: a small browser panel on `127.0.0.1:8180` with the console, players, worlds, backups and the main switches. Sign in with the token from `/ashlar panel` (console only). Use an SSH tunnel or an HTTPS reverse proxy to reach it from outside.
- **Updates**: checks this repository's releases for a newer build of the same Minecraft version, verifies the SHA-256 and puts it in place when the server stops. `updates.download: false` only tells you about it.
- Its own start message in the console with the state of every feature.

## Commands (permission `ashlar.admin`)
| Command | What it does |
|---|---|
| `/ashlar` | Status of every feature |
| `/ashlar lag` | Busiest chunks and entities |
| `/ashlar reload` | Reload `ashlar.yml` |
| `/ashlar preset <name>` | Write tuned settings |
| `/ashlar unlock` | End an anti-bot lockdown |
| `/ashlar pregen [pause\|resume\|reset]` | Chunk pre-generation |
| `/ashlar backup [list]` | Back up now, or list backups |
| `/ashlar bans`, `ban-ip <ip> [minutes]`, `unban-ip <ip>` | IP bans |
| `/ashlar unregister <player>` | Remove a login account |
| `/ashlar update` | Check for a newer build now |
| `/ashlar plugins` | Plugin guard report |
| `/ashlar token`, `/ashlar panel` | Vote and panel tokens (console only) |

## Settings
`ashlar.yml` in the server folder. Every feature has its own switch.

## Download
The ready-to-run jar is on https://craftrank.org/ashlar (with its SHA-256).

## Running
Java 25. `java -Xmx4G -jar ashlar-26.2.jar --nogui`

## Building
```
./gradlew applyAllPatches
./gradlew :fork-server:createPaperclipJar
```
Our own code is in `fork-server/src/main/java/org/craftrank/ashlar`, changes to Paper are in `fork-server/paper-patches`.

## License
GPL-3.0, see `LICENSE`. Third-party parts and bundled plugins are listed in `NOTICE.md`.
