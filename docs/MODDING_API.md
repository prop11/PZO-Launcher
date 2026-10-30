# PZOApi - making your mod compatible with PZO

Project Zomboid Optimiser (PZO) speeds the game up partly by changing how often things run: other mods' per-frame
scripts are paced to 60 Hz, scripts on far zombies run every few frames, unseen zombies update less often, and the
optional cleanup removes corpses and ground items. Most mods never notice. If yours does - it counts frames, times
actions per tick, drives zombies from Lua, or keeps special corpses or items on the ground - `PZOApi` lets your mod
tell PZO how to treat it, without depending on PZO.

Available from PZO 1.6 (API version 1), Build 42.

- [Quick start](#quick-start)
- [How it behaves](#how-it-behaves)
- [Reference](#reference)
  - [Detection](#detection)
  - [Script pacing](#script-pacing)
  - [Zombies](#zombies)
  - [Cleanup](#cleanup)
  - [Stand-down](#stand-down)
  - [Caches](#caches)
- [Setting keys](#setting-keys)
- [Recipes](#recipes)
- [Troubleshooting](#troubleshooting)

## Quick start

Put this at the top of any Lua file of your mod (shared, client or server):

```lua
require "PZOApi"   -- when PZO is not installed this only logs: require("PZOApi") failed

if PZOApi then
    PZOApi.exemptMod("MyModId", "times its attacks in OnTick")
end
```

That is all most mods need. `MyModId` is the `id=` from your `mod.info`.

Do not list PZO as a requirement of your mod: everything works when PZO is missing, and every PZOApi call does
nothing harmful when the PZO Java engine is not running (only the Lua part of PZO is installed).

## How it behaves

- **Registrations are rebuilt on every Lua load.** The game builds a fresh Lua state when it starts, when a player
  enables or disables mods in game, and when they leave a save. PZOApi starts empty each time, your file registers
  again as it loads, and PZO sends the result to its Java engine after every load. A mod that was disabled leaves
  nothing behind, so you never need to undo a registration when your mod is turned off.
- **Register while your files load.** `exemptMod` and `exemptHandler` apply at once, also when called later, but
  calling them at file load means your handlers are left alone from their first call.
- **The player's settings are never overwritten.** A stand-down holds a setting off while your mod is enabled; the
  player's saved choice is kept and comes back when the stand-down ends.
- **Cheap when unused.** Every check PZO adds for the API is a plain field test until some mod uses that part of it.
- **Versioned.** `PZOApi.API_VERSION` only goes up when something is added; existing calls keep working.

## Reference

All functions return `true` on success and `false` (never an error) when the arguments are wrong.

### Detection

| Function | Returns |
|---|---|
| `PZOApi.API_VERSION` | `1` for this document. |
| `PZOApi.getVersion()` | PZO's version string, e.g. `"1.6.0"` (the Java engine's when it runs, otherwise the Lua mod's). |
| `PZOApi.isEngineActive()` | `true` when the PZO Java engine is running. Most engine-level optimisations need it. |
| `PZOApi.isFeatureActive(key)` | `true` when the PZO setting `key` is on and in effect: switched on, not held off by a mod, and the Java engine runs if the setting needs it. See [Setting keys](#setting-keys). |
| `PZOApi.getFeatures()` | Sorted list of every on/off PZO setting key. |

```lua
if PZOApi and PZOApi.isFeatureActive("Mod_ZombieScriptLOD") then
    -- your OnZombieUpdate handler runs every 3rd/6th frame on far zombies unless you exempt it
end
```

### Script pacing

PZO paces other mods' `OnTick` and `OnPlayerUpdate` handlers to 60 runs a second on average above 60 FPS (every
handler still runs on a frame with a key or mouse press), and runs other mods' `OnZombieUpdate` handlers every
frame within 15 tiles of a player, every 3rd frame to 30 tiles and every 6th beyond.

| Function | Effect |
|---|---|
| `PZOApi.exemptMod(modId, reason)` | Every `OnTick`, `OnPlayerUpdate` and `OnZombieUpdate` handler in your mod's files runs on every event. `reason` is optional and only logged. A leading backslash in the ID (Build 42 mod lists) is fine. |
| `PZOApi.exemptHandler(fn)` | The same for one handler: pass the function you give to `Events.X.Add`. |

Use these if your handler counts frames, times something in ticks, reads `isKeyPressed` on a later frame than the
press, or applies per-zombie behaviour that must not lapse.

```lua
local function onZombieUpdate(zombie) ... end
Events.OnZombieUpdate.Add(onZombieUpdate)
if PZOApi then PZOApi.exemptHandler(onZombieUpdate) end
```

### Zombies

Mark zombies that your mod drives from Lua (NPCs, special infected, bosses). PZO then updates a marked zombie like
one next to the player: your scripts on it are not staggered, its update rate is not lowered while no player sees it,
and it looks for doors, windows and fences ahead on every frame.

| Function | Effect |
|---|---|
| `PZOApi.markZombie(zombie)` | Marks the zombie. |
| `PZOApi.markZombie(zombie, false)` | Removes the mark. |
| `PZOApi.isZombieMarked(zombie)` | `true` when it is marked. |
| `PZOApi.useZombieMarks()` | Call once while your mod loads if marked zombies can come from a save (see below). |

The mark is the modData key `PZOFullRate = true`, so it is saved with the zombie. You can also set that key yourself;
then call `PZOApi.useZombieMarks()` once at load, because PZO only reads the key after some mod said it uses marks.

Mark only the zombies that need it: a marked zombie costs as much as one standing next to the player.

```lua
if PZOApi then PZOApi.useZombieMarks() end

local function makeBoss(zombie)
    zombie:getModData().MyMod_Boss = true
    if PZOApi then PZOApi.markZombie(zombie) end
end
```

### Cleanup

PZO's optional automatic and manual cleanup removes corpses and ground items (casings, trash, twigs, glass, rotten
food). Player corpses, favourited items and Project A-Life NPCs are always kept. To keep yours:

| Function | Effect |
|---|---|
| `PZOApi.protect(object)` | Never removes this object. Works on an `IsoDeadBody`, an `IsoWorldInventoryObject` or the `InventoryItem` of a ground item. Sets the modData key `PZONoCleanup = true`, which you can also set yourself. |
| `PZOApi.protect(object, false)` | Removes the protection. |
| `PZOApi.addCleanupFilter(modId, fn)` | `fn(object, kind)` returns `true` to keep the object. `kind` is `"corpse"` (`IsoDeadBody`) or `"item"` (`IsoWorldInventoryObject`). A filter that errors is ignored. |
| `PZOApi.shouldKeep(object, kind)` | What PZO asks before removing anything: `true` when a mod keeps it. |

Filters run for every candidate during a cleanup pass, so keep them quick (a modData lookup, not a world search).

```lua
if PZOApi then
    PZOApi.addCleanupFilter("MyModId", function(obj, kind)
        if kind ~= "corpse" or not obj:hasModData() then return false end
        return obj:getModData().MyMod_QuestCorpse == true
    end)
end
```

### Stand-down

If one PZO optimisation conflicts with your mod and nothing above solves it, turn just that one off while your mod is
enabled. PZO's other gains stay. The player sees "Off for <your mod>: <reason>" on that setting in the PZO settings
screen and can not switch it back on while your mod holds it.

| Function | Effect |
|---|---|
| `PZOApi.requestStanddown(key, modId, reason)` | Holds the setting `key` off. Use a short reason a player understands. |
| `PZOApi.releaseStanddown(key, modId)` | Ends your stand-down (another mod's can not be released). |
| `PZOApi.getStanddown(key)` | The mod ID and reason holding `key` off, or `nil`. |

Prefer the narrow tools first (`exemptMod`, `markZombie`, `protect`); a stand-down turns the optimisation off for
every zombie, script or object, not only yours. Please also open an issue at
https://github.com/prop11/PZO-Launcher/issues so the conflict can be fixed in PZO itself.

```lua
if PZOApi then
    PZOApi.requestStanddown("JVM_CrowdEdgeMemo", "MyModId", "moves walls every tick")
end
```

### Caches

PZO caches a few answers the game would otherwise work out again every frame. It clears them on the game's own
change events; tell it when your mod changes things in a way the game does not report.

| Call | When |
|---|---|
| `PZOApi.invalidate("walls")` | You changed walls, doors or windows directly from Lua (not through the game's build, door or window actions). Clears the crowd push-apart wall memo. |
| `PZOApi.invalidate("scripts")` | You changed item or recipe scripts at runtime. Clears the script lookup cache. |

Returns `false` for an unknown name or when the Java engine is not running.

## Setting keys

The keys `isFeatureActive` and `requestStanddown` take. `PZOApi.getFeatures()` returns the full list for the
installed version. The ones most likely to matter to other mods:

| Key | Setting | What it changes |
|---|---|---|
| `Mod_PacedEventRate` | Mod Per-Frame Scripts at 60 Hz | Other mods' `OnTick` / `OnPlayerUpdate` at 60 Hz. Prefer `exemptMod`. |
| `Mod_ZombieScriptLOD` | Mod Zombie Scripts LOD | Other mods' `OnZombieUpdate` staggered on far zombies. Prefer `exemptMod` or `markZombie`. |
| `JVM_HordeHibernation` | Slower Updates for Unseen Zombies | Unseen zombies update at the rate the game itself uses at 30 FPS. Prefer `markZombie`. |
| `JVM_FarThumpCadence` | Far Zombies Check Doors Every Other Frame | Zombies over 20 tiles away. Prefer `markZombie`. |
| `JVM_CrowdEdgeMemo` | Crowd Push-Apart Wall Memo | Wall test between squares cached per frame. See `invalidate("walls")`. |
| `JVM_ZombieArrivalPacing` | Pace Zombie Arrivals | Zombies created next to a horde spread over a few frames. |
| `JVM_SilentZombieAudio` | Silent Zombies Skip Sound Upkeep | No sound parameter updates on zombies making no sound. |
| `JVM_NoiseSweep` | Clear Old Noises Only When One Expires | Per-chunk noise list cleanup. |
| `JVM_ZombieTravelStats` | Zombie Travel Statistics Once a Frame | Statistics and achievements folded per frame. |
| `Horde_CullDistantAttachments` | Distant Zombie Attachment Culling | Attachments on far zombies not drawn. |
| `Horde_AudioConcurrencyLimit` | Horde Voice Limit | Limits zombie voices playing at once. |
| `JVM_BytecodeBloodCap` | One Blood Splat per Tile | Blood splats per tile capped. |
| `Corpse_AutoClean` | Automatic Corpse Cleanup | Off by default. Prefer `protect` / `addCleanupFilter`. |
| `Debris_AutoClean` | Automatic Debris Cleanup | Off by default. Prefer `protect` / `addCleanupFilter`. |
| `GFX_ForestCanopyCull` | Bake Distant Swaying Trees | Distant tree sway baked. |
| `JVM_FSREnabled` | AMD FSR 1.0 Upscaling | World drawn at a lower resolution and upscaled. |

Settings that only take effect at launch or world load (for example the chunk load distance) follow a stand-down at
the next launch or load.

## Recipes

**NPC or special-zombie mod.** Mark the zombies you drive, exempt your scripts, keep their corpses:

```lua
require "PZOApi"
if PZOApi then
    PZOApi.exemptMod("MyNPCs", "drives NPC zombies from OnZombieUpdate")
    PZOApi.useZombieMarks()
    PZOApi.addCleanupFilter("MyNPCs", function(obj, kind)
        return kind == "corpse" and obj:hasModData() and obj:getModData().MyNPCs_Id ~= nil
    end)
end

function MyNPCs.spawn(zombie)
    if PZOApi then PZOApi.markZombie(zombie) end
end
```

**Combat or timing mod** that counts ticks:

```lua
require "PZOApi"
if PZOApi then PZOApi.exemptMod("MyCombat", "hit windows counted in OnTick") end
```

**Map or base-building mod** that edits walls from Lua:

```lua
square:AddSpecialObject(wall)
if PZOApi then PZOApi.invalidate("walls") end
```

**Reacting to PZO** without changing it:

```lua
if PZOApi and PZOApi.isEngineActive() then
    print("MyMod: running with PZO " .. PZOApi.getVersion())
end
```

## Troubleshooting

- PZO logs every registration to `console.txt` as `[PZOApi] ...`, and the Java engine logs stand-downs as
  `[ModApi] <key> stood down for <mod>: <reason>`.
- `require("PZOApi") failed` in the log means PZO is not installed or not enabled. That is expected for players
  without PZO; your `if PZOApi then` guard skips the calls.
- `exemptMod` matches your mod by the folder its files load from (`/mods/<folder>/`), looked up from the mod ID. If
  your Workshop folder name differs from the ID and the mod is not found by ID, pass the folder name instead.
- Report conflicts PZOApi can not solve at https://github.com/prop11/PZO-Launcher/issues.
