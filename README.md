# Dynamic Stage

Dynamic Stage is a Forge 1.20.1 prototype for isolated encounter and STG stage instances. Distant Horizons owns all LOD database access, caching, mesh generation, and rendering. Dynamic Stage only selects a client-local LOD package, supplies a virtual source position, and synchronizes small stage-control messages.

There is no server-side LOD bake or Dynamic Stage LOD renderer. Voxy/DH remains responsible for client-side LOD rendering. Since 1.3.0, a server may optionally publish a verified LOD archive offer; the client downloads and mounts it before entering the stage.

## Current scope

- Multiple active stage instances use separate regions in `dynamicstage:stg_stage`.
- An instance can be single-player (`capacity = 1`) or accept multiple players.
- All members share the stage ID, LOD package, LOD anchor, region, capacity, and CMDCam flight epoch.
- The physical stage region and LOD source anchor are independent. Changing the anchor does not move players or copy terrain.
- Stage block interaction and breaking are denied server-side.
- The client must mount and validate its LOD package before the server teleports it.
- The persistent player marker `DynamicStageInstance` contains the active instance UUID for KubeJS or other orchestration.

The implementation uses Architectury. Forge/Distant Horizons and Fabric/Voxy development paths are available; Voxy is the preferred backend and DH remains a supported fallback.

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

The package is selected as `stages:forest`. Dynamic Stage validates the manifest, rejects symbolic-link paths, checks the SQLite header, mounts the directory through DH's save-structure override, and forces DH read-only while the stage is active. A local package stays client-only unless a server administrator places it in the server's portable `dynamicstage/lodpacks` resource tree for automatic distribution.

Packages can also be created from another save or another game instance with the
client-only command:

```text
/dstage lod import link <pack_id> <source_path>
/dstage lod import link-relative <pack_id> <source_path>
/dstage lod import copy <pack_id> <source_path>
/dstage lod export <pack_id> <archive.dstlod>
/dstage lod root
/dstage lod downloads status
/dstage lod downloads on|off
/dstage lod downloads max <MiB>
```

`/dstage lod downloads` controls the client-only server distribution policy.
The default allows offered downloads up to `256 MiB`; the limit can be set from
`1` to `512 MiB`. Turning downloads off rejects `required` offers and reports a
warning for `optional` offers, while an already installed local package remains
usable. The same settings are available on the editor's `Local` tab and are
stored in `config/dynamicstage-client.json` as `allow_server_lod_downloads` and
`max_server_lod_download_mib`.

`source_path` can point at a `DistantHorizons.sqlite` file, a DH dimension
directory, a Voxy `storage` directory, a Voxy world directory, a save
directory, or an instance directory. The importer scans for exactly one native
cache, detects DH versus Voxy, and writes the manifest and package layout
automatically. If multiple dimensions or worlds are found, point the command
at the specific cache instead of allowing an ambiguous selection.
An existing package ID is never overwritten; use a new ID or remove the old
client-local package deliberately before importing it again.

Stock Distant Horizons 3.2 packages can be cropped and compacted without a DH
fork:

```text
/dstage lod optimize dh crop <source_pack> <output_pack> <min_y> <max_y>
/dstage lod optimize dh radius <source_pack> <output_pack> <anchor_x> <anchor_z> <radius> <min_y> <max_y>
```

The optimizer takes a consistent SQLite snapshot into a new package, decodes
every retained `FullData` detail level through DH's own 3.2 DTOs, clips vertical
segments to the inclusive Y range, and optionally removes X/Z columns outside
the source-anchor radius. It clears immutable-package update metadata, crops
beacon records, drops migrated legacy data, and vacuums the output database.
The source package is never edited and the command refuses to overwrite an
existing output ID. Keep the source Minecraft instance closed while optimizing.
The output manifest records the policy, crop, consistent source-snapshot
SHA-256, and output database SHA-256. The source database, WAL, and shared-memory
file metadata must remain stable while the snapshot is taken. This first
stock-DH optimizer deliberately does not claim Voxy's solid
interior shell collapse; all retained DH surface and cave segments inside the
selected range remain native DH data.

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

### Server LOD distribution

No HTTP service, export command, or distribution command is required for a
server-owned package. Copy the same unpacked package directory into the server
instance root:

```text
<server>/dynamicstage/lodpacks/<namespace>/<path>/
  manifest.json
  voxy/ or dh/
```

For example, `minecraft:gr` is discovered from
`dynamicstage/lodpacks/minecraft/gr`. Dynamic Stage creates and caches a
`.dstlod` under `dynamicstage/.cache/lodarchives`, computes its SHA-256, and
streams missing bytes to the client in bounded packets. The generated cache is
not part of the distributable resource tree. An already exported archive at
`<server>/dynamicstage/lodpacks/minecraft/gr.dstlod` is also accepted, as is the
legacy `<world>/dynamicstage/lodpacks` archive location. The client resumes an
interrupted `.part` file and performs the same archive and package validation
as an HTTP download. Convention-based server packages are optional by default;
a missing or rejected package therefore does not prevent stage entry.

`/dstage lod export` creates an immutable `.dstlod` ZIP from a self-contained
client package. It rejects linked packages, skips RocksDB lock and diagnostic
log files, preserves WAL files, and prints the exact archive size and SHA-256.
Close the source Voxy/DH instance before exporting.

Publish the archive from an HTTP(S) server or CDN, then configure the game
server, for example:

```text
/dstage distribution set minecraft:gr optional 12585178 <sha256> https://cdn.example.invalid/gr.dstlod
```

The catalog is stored in the server world at
`dynamicstage/lod-distribution.json`. `required` blocks stage entry or a live
backdrop switch until the verified package is installed. `optional` allows the
operation to continue without a backdrop and reports a warning when download
or installation fails. With no catalog entry, the existing local-package
behavior is unchanged.

Clients keep archives under `.minecraft/dynamicstage/downloads` and install
validated packages under `.minecraft/dynamicstage/lodpacks`. Downloads use a
`.part` file and HTTP Range resume when available. Size, SHA-256, ZIP paths,
entry count, extracted size, and the native package manifest are checked before
an atomic installation. The server transfers only offer metadata; the archive
normally does not travel through the Minecraft tick/network channel. The
exception is a convention-based server archive, which uses a bounded,
tick-paced Dynamic Stage transfer and is intended for small and medium packs.
Use HTTP/CDN for very large archives or many simultaneous clients so the game
server does not become the file distribution bottleneck.

DH 3.2 still requires the database file and its directory to be writable when opening it, and may apply its own schema migrations. "Read-only" here means DS asks DH to stop LOD updates, generation, and network retrieval while the stage is active; it is not a SQLite read-only connection. Distribute a writable package produced by the same supported DH version and keep an immutable source copy outside the live instance when exact byte preservation matters.

## Commands

Stage creation, editing, distribution, and server settings require permission
level 2. Player-facing join, invite, exit, and GUI commands do not. The short
`/dstage start <stage>` form is registered on the client and forwards the
expanded command to the server:

```text
/dstage start <stage>
/dstage editor
/dstage gui
/dstage start <stage> <lod_pack> <source_x> <source_y> <source_z> [capacity]
/dstage join <slot(stage_name)>
/dstage invite <player>
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
/dstage backdrop dh-clip <scale>
/dstage backdrop voxy-near <distance>
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
/dstage lod export <pack_id> <archive.dstlod>
/dstage lod root
/dstage distribution list|path
/dstage distribution set <pack_id> <optional|required> <bytes> <sha256> <url>
/dstage distribution remove <pack_id>
/dstage reload
/dstage guide on|off|status
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
its `.minecraft/dynamicstage/<name>.json` name, bind or clear that Flight, edit instance,
boundary, backdrop, sky, and client-time settings, then save and start through the normal
LOD readiness handshake. Inside a stage, `Capture` snapshots the edited arena
and applies settings that can change on a live instance. LOD package and
capacity changes take effect on the next instance. The editor sends only
bounded template summaries and flight bind/clear actions; LOD databases, arena snapshots, and flight files
remain local to their existing client/server stores.

`/dstage gui` opens the player-facing instance browser and local render
settings. Active instances use a stable one-based slot label such as `3(gr1)`;
hover the label to see its UUID for diagnostics. `/dstage invite <player>` sends
a two-minute clickable invitation for the sender's current instance. Capacity
is checked again when the invitation is accepted.

Players teleported directly into the isolated region of another active
instance are attached to that instance automatically. If capacity is
available they become a normal member; otherwise they receive the same client
scene and boundary as a spectator without consuming capacity. Leaving restores
the pre-stage game mode and last tracked non-stage return point.

While inside a stage, select any saved template with the editor's arrow controls
and press `Reload` to replace the current instance in place. The instance UUID,
slot, members, and return locations remain stable while the selected arena,
boundary, LOD binding, Flight, and client scene are applied to every member.

Portable stage template manifests are directly editable UTF-8 JSON files at
`dynamicstage/templates/<id>.json`. Captured vanilla structure data is stored separately
as a content-addressed `dynamicstage/arenas/<sha256>.nbt`; templates with identical
arena contents share that file, and deleting the final reference removes it. Copying one
`dynamicstage` directory to another game or server instance therefore makes the same
authored stages available there. Retired hashed template `.dat` files already present in
`dynamicstage/templates` are converted once, verified, and removed when templates are first
listed or `/dstage reload` runs. The older `config/dynamicstage/templates` store is not scanned.
Saving a template captures the active LOD package ID and anchor, boundary, client time
and backdrop settings, player movement scale, capacity, CMDCam flight data, and
the boundary's blocks, block entities, and non-player entities. Native LOD data
is still referenced by its client-local package ID and is not copied into the
template. A named template Flight is stored as a reference to its global JSON name;
an unnamed active Flight is embedded as a JSON object so it is not lost. Global imported flights are stored as one directly editable, validated
`<name>.json` per animation under `.minecraft/dynamicstage`; `/dstage flight use <stage> [flight_name]`
installs one into another save. `shared` reuses one live instance up to its capacity; `parallel`
creates an isolated instance for every start. With capacity 1, `parallel` gives each player a
separate instance, while a full `shared` instance rejects later entries instead of allocating
another shared copy. `on_create` restores the arena
snapshot when a new instance is allocated, while `manual` leaves the slot alone
until `/dstage template reset` is run. Changing a saved template's boundary size
clears its incompatible arena snapshot; use `Capture` to record the resized arena.
Arena snapshots are sparse: air is omitted, while non-air blocks, block entities,
entities, and the capture dimensions remain in vanilla structure data. Restore clears
the target box before placing that sparse structure. Captures are limited to 256 x 256
x 128 and 4,194,304 scanned blocks; the saved size now scales primarily with actual
arena content rather than empty volume. Mod state stored only in another mod's global
SavedData is outside the vanilla structure snapshot and requires an explicit
compatibility adapter. Loquat areas are supported through its structure snapshot
hook; Dynamic Stage clears matching target areas before restore so reused instance
slots do not fail on duplicate bounds.

The six virtual boundary walls constrain players, mobs, and other non-projectile
entities without placing blocks. Projectiles intentionally pass through them.
Each wall uses its own inward distance: crossing a wall keeps that entire wall
fully visible, while walls still inside the boundary only render their nearby
grid segment and fade over the configured client distance. A server-recorded
wall color takes priority; a boundary set to `client` uses the local fallback.
The editor's `Local` tab applies distance, opacity, and fallback-color changes immediately and saves
them only to this game instance's `config/dynamicstage-client.json`.

`/dynamicstage` remains available as a compatibility alias for server commands. `dstage sky` is client-only: `overworld` is the default normal Overworld sky renderer, `end` selects the End sky renderer, and `off` suppresses sky and cloud rendering inside the stage. While a flight is active, the selected sky shares its yaw, pitch, roll, and FOV transform with the LOD backdrop. Vanilla clouds additionally use the same virtual source position as the LOD backdrop, including the anchor, player-follow mode, and flight XYZ; the infinite-distance sky dome ignores translation.

`/dstage reload` rescans and validates portable template JSON, arena NBT, global Flight JSON,
and server LOD packages without restarting the server. It invalidates generated
archive hashes, rebuilds archives for unpacked packages, reports invalid resources,
and refreshes the Editor template list
for online operators. It does not mutate an already running stage instance;
use the Editor's Reload action when the updated template should be applied to
that instance. Data-pack templates are owned by Minecraft's resource manager;
after adding or deleting KubeJS `data/<namespace>/dynamicstage/stages/*.json`, run
`kjs reload` (or the full vanilla data-pack reload) before `/dstage reload`.

Stage flights do not replace Minecraft's main camera or a shader pack's shadow camera. Stage blocks, entities, particles, and shadow-map movement therefore remain attached to the player; only the mounted LOD viewport and the local vanilla sky/cloud passes receive the flight transform. Iris/Oculus shader packs that retain those vanilla passes can render them through their normal pipeline. A pack that disables vanilla sky or clouds and draws its own procedural replacement requires a pack-specific integration before that replacement can follow stage flights. Client stage time is intentionally visible to the rendering pipeline, so shader packs may move their sun, ambient lighting, and time-derived shadows when `/dstage time` changes.

`start` creates an instance, validates the local package, and then teleports. Once the target level wrapper exists, the client rebinds DH to the external database before flight playback begins; a failed rebind returns the player safely. `join` joins an active instance if capacity remains. `anchor` updates the shared virtual DH source position for every active or preparing member. `exit` restores the player's original dimension, position, and rotation.

Backdrop and time settings belong to the instance and are broadcast to all members. Player movement following is enabled by default. The movement multiplier applies only to player translation inside the stage; eye height and first/third-person camera offsets remain at 1x so changing perspective does not shift the LOD scene. Turning following off pins the native LOD camera to the source anchor while preserving those camera offsets and CMDCam flight motion. Stage time is evaluated only by the client: `follow` advances from a synchronized Overworld epoch, `fixed` holds a vanilla day-time value in the `0..23999` range, and `cycle` maps one visual Minecraft day onto the configured number of client ticks. These modes do not change server-side stage time or send per-tick network updates.

LOD visibility and live LOD package or Flight replacements can be instant, fade, or blur transitions. Persistent blur is independent from transitions and uses a `0..32` pixel radius; `0` disables it. DH's shader fade and projection clipping are separate template settings: `dh_near_fade_scale` changes only the soft handoff, while `dh_near_clip_scale` changes the actual projection near plane (`0.0001..1`, default `0.01`) without disabling frustum culling. A mounted Voxy stage uses the template's `voxy_near_plane` projection value (`0.01..16`, default `0.5`) because its sparse foreground cannot cover the native `8/16` handoff. The largest value that does not visibly clip nearby LOD provides the best distant depth precision. With `voxy-culling=false`, the matching HDRS Voxy build also preserves camera-adjacent LOD sections while leaving normal frustum culling intact. Normal-world Voxy rendering remains unchanged. Dynamic Stage filters only the native backend's intermediate LOD color texture before DH or Voxy performs its original depth-aware composite, so the sky, stage blocks, entities, and UI are not blurred.

For stock DH 3.2.0-b, DS hooks
`GlDhApplyShader_forge`, preserving DH's native depth-aware composite while
replacing only its color attachment during a transition/filter. Package
show/hide, fade, blur, persistent blur, Flight swaps, and live package switches
therefore use the same client scene state as Voxy. Mounting a DH package checks
that this integration Mixin is active and reports a compatibility error instead
of silently rendering without the requested filter.

Version 1.3.0 adds server-offered LOD archives while retaining command-level runtime scheduling. Repeated `/dstage backdrop switch`, `/dstage flight play`, and `/dstage flight stop` commands can combine any number of named LOD packages and global Flights during one instance. Each command applies to every member of that instance. KubeJS or another server script can issue them from music markers, player NBT, or timed events; Flight motion uses a shared server game-time epoch so all clients sample the same animation position.

## CMDCam and music

A validated CMDCam scene can be attached to a stage. `/dstage flight import` first reads CMDCam's live server SavedData, so a freshly saved scene is immediately available for tab completion without `/save-all`. It checks the command's current dimension and then the Overworld. Modern 26.2 files under `dimensions/<namespace>/<dimension>/data/cmdcam/scenes.dat`, legacy `cmdcam_Scenes.dat`, and explicit external files are accepted. A single-scene external file can be imported without naming the scene; multi-scene files report the available names. Dynamic Stage sends the small scene JSON and a server game-time epoch, then samples it locally without starting CMDCam playback. The player keeps normal movement and camera control while XYZ animates the mounted LOD background and yaw, pitch, roll, and zoom animate both the LOD and selected sky. Every attribute is relative to the first path point, so playback starts without a jump. `loop -1` repeats forever; finite loops retain CMDCam's final normal pass.

CMDCam and CreativeCore are included in the Forge development runtime for authoring and importing paths, but clients playing an already imported path do not need either mod. For a local compatibility test, author at least two visibly different points with `/cam add`; include changes to yaw, pitch, roll, and zoom as well as position. Set `/cam loops -1` for a continuously moving backdrop and save it with `/cam save <scene>`. Both `default` and `outside` modes are accepted, and Dynamic Stage ignores `smooth_start`. Type `/dstage flight import test ` and select the scene from tab completion, then import it before starting that same stage ID.

Global Flight library files are plain UTF-8 JSON objects and can be edited
directly in VS Code. They must contain one selected scene object rather than a
CMDCam export array. Dynamic Stage validates the file whenever it is bound or
played; no archive or NBT repacking step is required. Files using the retired
`.dat` wrapper are intentionally ignored.

Music remains the responsibility of `mob-battle-music`. KubeJS can combine its marker/state with `DynamicStageInstance` rather than requiring a second music protocol in Dynamic Stage.

## Development

Build and run tests:

```powershell
.\gradlew.bat clean build
```

The release outputs are `fabric/build/libs/dstage-fabric-1.6.3.jar` and
`forge/build/libs/dstage-forge-1.6.3.jar`. They do not embed DH, Voxy, CMDCam,
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

## License

Dynamic Stage is licensed under the [MIT License](LICENSE). Optional
dependencies and user-provided stage resources retain their own licenses; see
[THIRD_PARTY.md](THIRD_PARTY.md) for the boundary between this project and its
integrations.

See [docs/REPOSITORY_REVIEW.md](docs/REPOSITORY_REVIEW.md) for the current architecture review and remaining work. The proposed return to a legally distributable, stage-specialized DH backend is tracked in [docs/DH_STAGE_BACKEND_RESEARCH_PLAN.md](docs/DH_STAGE_BACKEND_RESEARCH_PLAN.md).
