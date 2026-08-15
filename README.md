# Dynamic Stage

Dynamic Stage is a Forge 1.20.1 prototype for isolated encounter and STG stage instances. Distant Horizons owns all LOD database access, caching, mesh generation, and rendering. Dynamic Stage only selects a client-local LOD package, supplies a virtual source position, and synchronizes small stage-control messages.

There is no server-side LOD bake, LOD file transfer, or Dynamic Stage LOD renderer. Players must already have the selected package, normally distributed as part of a modpack.

## Current scope

- Multiple active stage instances use separate regions in `dynamicstage:stg_stage`.
- An instance can be single-player (`capacity = 1`) or accept multiple players.
- All members share the stage ID, LOD package, LOD anchor, region, capacity, and CMDCam flight epoch.
- The physical stage region and LOD source anchor are independent. Changing the anchor does not move players or copy terrain.
- Stage block interaction and breaking are denied server-side.
- The client must mount and validate its LOD package before the server teleports it.
- The persistent player marker `DynamicStageInstance` contains the active instance UUID for KubeJS or other orchestration.

The implementation uses Architectury. Forge/Distant Horizons and Fabric/Voxy development paths are available; DH remains the primary compatibility target.

## Client LOD packages

Packages are addressed by a Minecraft resource ID and live outside world saves:

```text
.minecraft/dynamicstage/lodpacks/<namespace>/<path>/
  manifest.json
  dh/
    DistantHorizons.sqlite
```

Example `.minecraft/dynamicstage/lodpacks/stages/forest/manifest.json`:

```json
{
  "formatVersion": 1,
  "backend": "distanthorizons",
  "minecraftVersion": "1.20.1",
  "distantHorizonsVersion": "3.2",
  "minY": -64,
  "height": 384
}
```

The package is selected as `stages:forest`. Dynamic Stage validates the manifest, rejects symbolic-link paths, checks the SQLite header, mounts the directory through DH's save-structure override, and forces DH read-only while the stage is active. The database is never sent over the Dynamic Stage network channel.

Packages can also be created from another save or another game instance with the
client-only command:

```text
/dstage lod import link <pack_id> <source_path>
/dstage lod import link-relative <pack_id> <source_path>
/dstage lod import copy <pack_id> <source_path>
/dstage lod root
```

`source_path` can point at a `DistantHorizons.sqlite` file, a DH dimension
directory, a Voxy `storage` directory, a Voxy world directory, a save
directory, or an instance directory. The importer scans for exactly one native
cache, detects DH versus Voxy, and writes the manifest and package layout
automatically. If multiple dimensions or worlds are found, point the command
at the specific cache instead of allowing an ambiguous selection.
An existing package ID is never overwritten; use a new ID or remove the old
client-local package deliberately before importing it again.

`link` writes only a small manifest containing the local absolute source path;
the native cache is mounted in place and consumes no second copy.
`link-relative` instead records a portable path relative to the package's
`manifest.json`. Its source must be inside the current game instance. For a
distributed modpack, keep both trees under that instance, for example
`dynamicstage/lodpacks` and `dynamicstage/lodsources`, and ship them together.
The relative path uses portable `/` separators and survives a different
launcher instance directory, user name, drive letter, or operating system.
`copy` creates an isolated, self-contained writable cache
inside the package and remains the recommended mode when the source was
produced by another Minecraft, DH, or Voxy version. All modes require the
source game instance to be closed. A link still needs a writable source because
DH/Voxy may create locks or perform schema migration; DS only disables
stage-time generation and network retrieval, it cannot turn the backend's file
format into a true read-only connection. Source paths remain client-local and
are never sent to the server.

DH 3.2 still requires the database file and its directory to be writable when opening it, and may apply its own schema migrations. "Read-only" here means DS asks DH to stop LOD updates, generation, and network retrieval while the stage is active; it is not a SQLite read-only connection. Distribute a writable package produced by the same supported DH version and keep an immutable source copy outside the live instance when exact byte preservation matters.

## Commands

Server-side commands currently require permission level 2. The short
`/dstage start <stage>` form is registered on the client and forwards the
expanded command to the server:

```text
/dstage start <stage>
/dstage editor
/dstage start <stage> <lod_pack> <source_x> <source_y> <source_z> [capacity]
/dstage join <instance_uuid>
/dstage template save <template> [parallel|shared] [on_create|manual]
/dstage template start <template>
/dstage template reset
/dstage template list
/dstage template delete <template>
/dstage anchor <source_x> <source_y> <source_z>
/dstage backdrop status
/dstage backdrop follow on|off
/dstage backdrop movement <scale>
/dstage backdrop dh-fade <scale>
/dstage backdrop voxy-culling <true|false>
/dstage backdrop switch <lod_pack> [fade|blur] [ticks]
/dstage backdrop show|hide
/dstage backdrop show|hide fade <ticks>
/dstage backdrop show|hide blur <ticks>
/dstage backdrop blur <radius>
/dstage time status
/dstage time follow
/dstage time fixed <day_time>
/dstage time cycle <period_ticks> [start_day_time]
/dstage exit
/dstage flight import <stage> <cmdcam_scene>
/dstage flight importfile <stage> <scenes.dat>
/dstage flight importscene <stage> <scenes.dat> <scene>
/dstage flight importjson <stage> <name> [slot]
/dstage flight use <stage> [flight_name]
/dstage flight library list
/dstage flight status <stage>
/dstage flight clear <stage>
/dstage flight play <flight|configured> [fade|blur] [ticks]
/dstage flight stop [fade|blur] [ticks]
/dstage sky overworld|end|off
/dstage lod import link <pack_id> <source_path>
/dstage lod import link-relative <pack_id> <source_path>
/dstage lod import copy <pack_id> <source_path>
/dstage lod root
```

`/dstage start <stage>` is a client-side convenience shortcut. It captures the
executor's current block position, identifies the single native DH or Voxy LOD
storage currently opened for that level, creates or reuses a client-local
relative link, and then sends the full server command. If no native cache is
available, the full command is still sent with an empty automatic package ID;
the player enters the stage with a warning and no LOD backdrop. If more than
one native backend is active, use the explicit form instead of guessing.

`/dstage editor` opens the client editor. Before entry it can select or create a
portable template, bind the current native LOD cache, select a global Flight by
its `.minecraft/dynamicstage/<name>.dat` name, bind or clear that Flight, edit instance,
boundary, backdrop, sky, and client-time settings, then save and start through the normal
LOD readiness handshake. Inside a stage, `Capture` snapshots the edited arena
and applies settings that can change on a live instance. LOD package and
capacity changes take effect on the next instance. The editor sends only
bounded template summaries and flight bind/clear actions; LOD databases, arena snapshots, and flight files
remain local to their existing client/server stores.

Portable stage templates are stored under `config/dynamicstage/templates`, so
they are shared by different saves in the same game or server instance. Saving
a template captures the active LOD package ID and anchor, boundary, client time
and backdrop settings, player movement scale, capacity, CMDCam flight data, and
the boundary's blocks, block entities, and non-player entities. Native LOD data
is still referenced by its client-local package ID and is not copied into the
template. Global imported flights are stored as one validated `<name>.dat` per
animation under `.minecraft/dynamicstage`; `/dstage flight use <stage> [flight_name]`
installs one into another save. `shared` reuses one live instance up to its capacity; `parallel`
creates an isolated instance for every start. `on_create` restores the arena
snapshot when a new instance is allocated, while `manual` leaves the slot alone
until `/dstage template reset` is run. Arena snapshots are limited to 256 x 256
x 128 and 4,194,304 blocks. Mod state stored only in another mod's global
SavedData is outside the vanilla structure snapshot and requires an explicit
compatibility adapter.

`/dynamicstage` remains available as a compatibility alias for server commands. `dstage sky` is client-only: `overworld` is the default normal Overworld sky renderer, `end` selects the End sky renderer, and `off` suppresses sky and cloud rendering inside the stage. While a flight is active, the selected sky shares its yaw, pitch, roll, and FOV transform with the LOD backdrop. Vanilla clouds additionally use the same virtual source position as the LOD backdrop, including the anchor, player-follow mode, and flight XYZ; the infinite-distance sky dome ignores translation.

Stage flights do not replace Minecraft's main camera or a shader pack's shadow camera. Stage blocks, entities, particles, and shadow-map movement therefore remain attached to the player; only the mounted LOD viewport and the local vanilla sky/cloud passes receive the flight transform. Iris/Oculus shader packs that retain those vanilla passes can render them through their normal pipeline. A pack that disables vanilla sky or clouds and draws its own procedural replacement requires a pack-specific integration before that replacement can follow stage flights. Client stage time is intentionally visible to the rendering pipeline, so shader packs may move their sun, ambient lighting, and time-derived shadows when `/dstage time` changes.

`start` creates an instance, validates the local package, and then teleports. Once the target level wrapper exists, the client rebinds DH to the external database before flight playback begins; a failed rebind returns the player safely. `join` joins an active instance if capacity remains. `anchor` updates the shared virtual DH source position for every active or preparing member. `exit` restores the player's original dimension, position, and rotation.

Backdrop and time settings belong to the instance and are broadcast to all members. Player movement following is enabled by default. Turning it off pins the native LOD camera to the source anchor while preserving first/third-person camera offsets and CMDCam flight motion. Stage time is evaluated only by the client: `follow` advances from a synchronized Overworld epoch, `fixed` holds a vanilla day-time value in the `0..23999` range, and `cycle` maps one visual Minecraft day onto the configured number of client ticks. These modes do not change server-side stage time or send per-tick network updates.

LOD visibility and live LOD package or Flight replacements can be instant, fade, or blur transitions. Persistent blur is independent from transitions and uses a `0..32` pixel radius; `0` disables it. DH near fade remains configurable. Voxy's stage projection always uses its depth-safe `0.1` near plane; `voxy-culling=false` preserves the LOD section containing the camera instead of changing projection depth. This Voxy override requires the matching HDRS Voxy build and leaves normal-world Voxy culling unchanged. Dynamic Stage filters only the native backend's intermediate LOD color texture before DH or Voxy performs its original depth-aware composite, so the sky, stage blocks, entities, and UI are not blurred.

Version 1.2.0 intentionally exposes command-level runtime scheduling rather than an internal cue timeline. Repeated `/dstage backdrop switch`, `/dstage flight play`, and `/dstage flight stop` commands can combine any number of named LOD packages and global Flights during one instance. Each command applies to every member of that instance. KubeJS or another server script can issue them from music markers, player NBT, or timed events; Flight motion uses a shared server game-time epoch so all clients sample the same animation position.

## CMDCam and music

A validated CMDCam scene can be attached to a stage. `/dstage flight import` first reads CMDCam's live server SavedData, so a freshly saved scene is immediately available for tab completion without `/save-all`. It checks the command's current dimension and then the Overworld. Modern 26.2 files under `dimensions/<namespace>/<dimension>/data/cmdcam/scenes.dat`, legacy `cmdcam_Scenes.dat`, and explicit external files are accepted. A single-scene external file can be imported without naming the scene; multi-scene files report the available names. Dynamic Stage sends the small scene JSON and a server game-time epoch, then samples it locally without starting CMDCam playback. The player keeps normal movement and camera control while XYZ animates the mounted LOD background and yaw, pitch, roll, and zoom animate both the LOD and selected sky. Every attribute is relative to the first path point, so playback starts without a jump. `loop -1` repeats forever; finite loops retain CMDCam's final normal pass.

CMDCam and CreativeCore are included in the Forge development runtime for authoring and importing paths, but clients playing an already imported path do not need either mod. For a local compatibility test, author at least two visibly different points with `/cam add`; include changes to yaw, pitch, roll, and zoom as well as position. Set `/cam loops -1` for a continuously moving backdrop and save it with `/cam save <scene>`. Both `default` and `outside` modes are accepted, and Dynamic Stage ignores `smooth_start`. Type `/dstage flight import test ` and select the scene from tab completion, then import it before starting that same stage ID.

Music remains the responsibility of `mob-battle-music`. KubeJS can combine its marker/state with `DynamicStageInstance` rather than requiring a second music protocol in Dynamic Stage.

## Development

Build and run tests:

```powershell
.\gradlew.bat clean build
```

The release outputs are `fabric/build/libs/dstage-fabric-1.2.0.jar` and
`forge/build/libs/dstage-forge-1.2.0.jar`. They do not embed DH, Voxy, CMDCam,
SQLite, RocksDB, or compression libraries.

Run the default Forge client with DH and Oculus shader compatibility:

```powershell
.\gradlew.bat :forge:runClient
```

Run the isolated DH-only client when shader compatibility itself needs to be
diagnosed:

```powershell
.\gradlew.bat :forge:runDhClient
```

Forge development clients never load DH and Voxy together. `runDhClient` loads
Distant Horizons 3.2, while `runClient`/`runOculusClient` add Embeddium and
Oculus 1.20.1-1.8.0 (the release with DH 2.2+ compatibility). `runVoxyClient`
loads the pinned Connector/Voxy pair. All profiles load CMDCam and CreativeCore.
Gradle downloads and verifies the Oculus CDN artifact into
`forge/build/development-libs`; no dependency is copied into `forge/run/mods`.

DH acceptance flow using the development layout:

```text
1. Put a DH 3.2 database at forge/run/dynamicstage/lodpacks/dev/overworld/dh/DistantHorizons.sqlite.
2. Put the matching manifest.json at forge/run/dynamicstage/lodpacks/dev/overworld/manifest.json.
3. Start with the command above and open/create a world.
4. Stand at the source location represented by the database and note X Y Z.
5. Run /dstage start test dev:overworld <X> <Y> <Z> 1.
6. Verify the stage has the normal sky and DH background, then move a short distance; the LOD must move 1:1 with the source-world camera mapping.
7. Import and attach a CMDCam flight, then verify the player can still move and turn normally while the LOD and clouds follow XYZ/yaw/pitch/roll/zoom and the infinite-distance sky follows yaw/pitch/roll/zoom.
8. Run /dstage anchor <newX> <newY> <newZ> and verify the backdrop jumps to the new source anchor.
9. Run /dstage sky off and /dstage sky overworld to verify client sky selection.
10. Run /dstage exit and verify the original dimension, position, DH database and interaction state are restored.
```

The `dev:overworld` database must be a writable runtime copy. Close Minecraft before replacing it and include any matching SQLite `-wal`/`-shm` state only after a clean DH shutdown.

Run the Voxy compatibility client separately:

```powershell
.\gradlew.bat :forge:runVoxyClient
```

For a Voxy package, set `backend` to `voxy`, provide `voxyVersion: "0.2.14"`
and `worldId` in the manifest, and place the matching RocksDB data under
`voxy/<worldId>/storage`. The Voxy profile follows Connector's official
development setup: Connector is loaded from ModLauncher's library classpath,
Forgified Fabric API is remapped for Forge, and the original intermediary Voxy
jar is transformed by Connector. None of these dependencies are copied into
`forge/run/mods`. The run first uses the matching output from the adjacent
`voxy-thirdparty-java17` checkout, then falls back to the Modrinth Maven artifact.

Run the CMDCam compatibility client (CMDCam and CreativeCore are development runtime dependencies and are not bundled in the output jar):

```powershell
.\gradlew.bat :forge:runClient
```

DH 3.2.0-b has been verified on Forge 1.20.1 with a real external database: both Dynamic Stage mixins apply, DH opens the selected package, anchor updates succeed, two package paths can be selected in one connection, and exit restores DH state without a level-change error. Multiplayer timing and visual comparison of CMDCam XYZ/yaw/pitch/roll/zoom still require acceptance tests.

See [docs/REPOSITORY_REVIEW.md](docs/REPOSITORY_REVIEW.md) for the current architecture review and remaining work.
