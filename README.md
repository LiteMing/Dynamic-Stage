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

DH 3.2 still requires the database file and its directory to be writable when opening it, and may apply its own schema migrations. "Read-only" here means DS asks DH to stop LOD updates, generation, and network retrieval while the stage is active; it is not a SQLite read-only connection. Distribute a writable package produced by the same supported DH version and keep an immutable source copy outside the live instance when exact byte preservation matters.

## Commands

Commands currently require permission level 2:

```text
/dstage start <stage> <lod_pack> <source_x> <source_y> <source_z> [capacity]
/dstage join <instance_uuid>
/dstage anchor <source_x> <source_y> <source_z>
/dstage exit
/dstage flight import <stage> <cmdcam_scene>
/dstage flight importjson <stage> <name> [slot]
/dstage flight status <stage>
/dstage flight clear <stage>
/dstage sky overworld|end|off
```

`/dynamicstage` remains available as a compatibility alias for server commands. `dstage sky` is client-only: `overworld` is the default normal Overworld sky renderer, `end` selects the End sky renderer, and `off` suppresses sky rendering inside the stage.

`start` creates an instance, validates the local package, and then teleports. Once the target level wrapper exists, the client rebinds DH to the external database before flight playback begins; a failed rebind returns the player safely. `join` joins an active instance if capacity remains. `anchor` updates the shared virtual DH source position for every active or preparing member. `exit` restores the player's original dimension, position, and rotation.

## CMDCam and music

A validated CMDCam scene can be attached to a stage. `/dstage flight import` first reads CMDCam's live server SavedData, so a freshly saved scene is immediately available for tab completion without `/save-all`. It checks the command's current dimension and then the Overworld. The `.dat` files are used only as a fallback. Dynamic Stage sends the small scene JSON and a server game-time epoch, then samples it locally without starting CMDCam playback. The player keeps normal movement and camera control while XYZ, yaw, pitch, roll, and zoom animate only the mounted LOD background. Every attribute is relative to the first path point, so playback starts without a jump. `loop -1` repeats forever; finite loops retain CMDCam's final normal pass.

CMDCam and CreativeCore are included in the Forge development runtime for authoring and importing paths, but clients playing an already imported path do not need either mod. For a local compatibility test, author at least two visibly different points with `/cam add`; include changes to yaw, pitch, roll, and zoom as well as position. Set `/cam loops -1` for a continuously moving backdrop and save it with `/cam save <scene>`. Both `default` and `outside` modes are accepted, and Dynamic Stage ignores `smooth_start`. Type `/dstage flight import test ` and select the scene from tab completion, then import it before starting that same stage ID.

Music remains the responsibility of `mob-battle-music`. KubeJS can combine its marker/state with `DynamicStageInstance` rather than requiring a second music protocol in Dynamic Stage.

## Development

Build and run tests:

```powershell
.\gradlew.bat clean test jarJar
```

The output is `build/libs/dynamicstage-0.1.0-all.jar`; it does not embed DH, CMDCam, SQLite, RocksDB, or compression libraries.

Run the DH compatibility client (the default Forge client is also DH-only):

```powershell
.\gradlew.bat :forge:runDhClient
```

Forge development clients never load DH and Voxy together. `runDhClient` loads
Distant Horizons 3.2, while `runVoxyClient` loads the pinned Connector/Voxy
pair. Both profiles load CMDCam and CreativeCore; only Voxy additionally loads
Embeddium.

DH acceptance flow using the development layout:

```text
1. Put a DH 3.2 database at forge/run/dynamicstage/lodpacks/dev/overworld/dh/DistantHorizons.sqlite.
2. Put the matching manifest.json at forge/run/dynamicstage/lodpacks/dev/overworld/manifest.json.
3. Start with the command above and open/create a world.
4. Stand at the source location represented by the database and note X Y Z.
5. Run /dstage start test dev:overworld <X> <Y> <Z> 1.
6. Verify the stage has the normal sky and DH background, then move a short distance; the LOD must move 1:1 with the source-world camera mapping.
7. Import and attach a CMDCam flight, then verify the player can still move and turn normally while only the LOD follows its XYZ/yaw/pitch/roll/zoom path.
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
