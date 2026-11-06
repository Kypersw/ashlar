# Ashlar

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
- No update checks against PaperMC servers.

## Commands (permission `ashlar.admin`)
`/ashlar` status · `/ashlar lag` · `/ashlar reload` · `/ashlar preset <name>` · `/ashlar unlock` (end anti-bot lockdown) · `/ashlar token` (console only)

## Settings
`ashlar.yml` in the server folder. Every feature has its own switch.

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
