# 🚀 Project Zomboid Dedicated Server Optimizer (PZO Server Engine)

**PZO Server Engine (`PZOServerEngine.jar`)** is a dedicated server optimization suite designed specifically for **3rd-Party Hosted Servers & Dedicated Server Nodes** (Indifferent Broccoli, G-Portal, Nitrado, GTXGaming, BisectHosting, Pterodactyl panels, Hetzner, OVH, Docker).

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

## 📦 3rd-Party Host Installation (Indifferent Broccoli / G-Portal / Pterodactyl / Docker)

### Step 1: Upload `PZOServerEngine.jar`
Using your hosting panel's **File Manager** or **SFTP/FTP client** (FileZilla / WinSCP):
1. Upload **`PZOServerEngine.jar`** into your server's root directory (`/project-zomboid/` or the folder containing `ProjectZomboid64.json`).

### Step 2: Add `-javaagent` to `ProjectZomboid64.json`
Open `ProjectZomboid64.json` in your host's File Manager:
1. Keep `"mainClass": "zombie/network/GameServer"` untouched.
2. Add `"-javaagent:PZOServerEngine.jar"` to the top of your `"vmArgs"` array.

#### Example `ProjectZomboid64.json`:
```json
{
  "mainClass": "zombie/network/GameServer",
  "classpath": [
    "java/.",
    "java/projectzomboid.jar"
  ],
  "vmArgs": [
    "-javaagent:PZOServerEngine.jar",
    "-Djava.awt.headless=true",
    "-Xmx6G",
    "-Dzomboid.server=1",
    "-Dzomboid.steam=1",
    "-Dzomboid.znetlog=1",
    "-Djava.library.path=linux64/",
    "-Djava.security.egd=file:/dev/urandom",
    "-XX:+UseZGC",
    "-XX:-OmitStackTraceInFastThrow",
    "-XX:+UseCompactObjectHeaders",
    "-XX:+UseStringDeduplication",
    "-XX:+PerfDisableSharedMem",
    "-XX:+DisableExplicitGC",
    "-XX:+ExitOnOutOfMemoryError"
  ]
}
```
3. Save the file and restart your server from your host web panel!

---

## 💡 Why `-javaagent` is Recommended for Linux Server Hosts
The native Linux launcher binary (`pzexe` / `ProjectZomboid64`) specifically checks if `"mainClass"` matches `"zombie/network/GameServer"`. If `mainClass` is modified, `pzexe` misidentifies the process as a desktop client and triggers a failing `SteamAPI_Init()` check. 

Using `-javaagent:PZOServerEngine.jar` keeps `mainClass` stock while automatically hooking all PZO server optimization pipelines before `GameServer` boots.

---

## ⚙️ Recommended Host RAM Sizing

Adjust `-Xmx` to match your server plan's allocated RAM:
* **4GB Server Plan**: `-Xms1536m -Xmx3584m`
* **6GB Server Plan**: `-Xms2048m -Xmx5120m`
* **8GB Server Plan**: `-Xms2048m -Xmx7168m`
* **16GB Server Plan**: `-Xms4096m -Xmx14336m`
* **32GB+ Dedicated Node**: `-Xms8192m -Xmx28672m`
