# 🚀 Project Zomboid Dedicated Server Optimizer (PZO Server Engine)

**PZO Server Engine (`PZOServerEngine.jar`)** is a dedicated server optimization suite designed specifically for **3rd-Party Hosted Servers & Dedicated Server Nodes** (G-Portal, Nitrado, GTXGaming, Indifferent Broccoli, BisectHosting, Pterodactyl panels, Hetzner, OVH, Docker).

---

## ⚡ Server Performance Features

1. **🌐 Zero-Rubberband Networking (`ServerNetworkTuner`)**:
   - Pools off-heap direct NIO memory buffers for 10–64+ concurrent players.
   - Allocates 4096 high-capacity UDP datagram sockets to eliminate ping spikes and desync during gunfire, driving, and horde combat.

2. **🧟 Multi-Core Zombie Pathfinding (`ServerHordeSimEngine`)**:
   - Dynamically scales server-side pathfinding and migration across all host CPU cores (up to 32/64 threads).
   - Hibernates distant zombie AI simulations when no players are in range.

3. **💾 Zero-Lag World Save Booster (`ServerChunkStreamBooster`)**:
   - 256KB asynchronous disk write buffering for SQLite `.db` tables (`players.db`, `vehicles.db`) and `map_*.bin` chunk saves.
   - Eliminates the periodic *"Server Saving World... Lag Spike"* that freezes active players.

4. **📊 Live Server Telemetry Bridge (`ServerTelemetryBridge`)**:
   - Streams live JSON metrics to `pzo_server_telemetry.json` (Real Server TPS, Heap MB, GC Pause ms, Thread counts) every 5 seconds. Perfect for Discord bot integrations and server monitoring dashboards.

5. **🤝 ZombieBuddy Server Coexistence**:
   - 100% compatible with `ZombieBuddy.jar` and all Workshop server mods.

---

## 📦 2-Step 3rd-Party Host Installation (FTP / Web Panel)

### Step 1: Upload `PZOServerEngine.jar`
Using your hosting panel's **File Manager** or **SFTP/FTP client** (FileZilla / WinSCP):
1. Upload **`PZOServerEngine.jar`** into your server's root directory (the same folder containing `ProjectZomboid64.json` / `StartServer64.bat` / `projectzomboid.jar`).

### Step 2: Configure `ProjectZomboid64.json`
Open `ProjectZomboid64.json` in your File Manager:
1. Change `"mainClass"` to:
   ```json
   "mainClass": "com/pzoptimizer/server/PZOServerEntrypoint",
   ```
2. Add `"PZOServerEngine.jar"` to the top of your `"classpath"` array:
   ```json
   "classpath": [
       "PZOServerEngine.jar",
       ...
   ]
   ```
3. Save the file and restart your server from your host web panel!

---

## ⚙️ Recommended Host Startup Parameters (Pterodactyl / Custom Command Line)

If your host allows editing custom JVM arguments in the panel Startup tab:
```text
-Xms4096m -Xmx16384m -XX:+UseG1GC -XX:InitiatingHeapOccupancyPercent=45 -XX:G1ReservePercent=15 -XX:+AlwaysPreTouch -Djava.awt.headless=true -Dzomboid.server=1 --enable-native-access=ALL-UNNAMED --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED
```
*(Adjust `-Xmx16384m` to your server's allocated RAM, e.g. `-Xmx32768m` for 32GB).*
