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

The current implementation is Forge-only and Distant Horizons-first. Architectury/Fabric and Voxy have not yet been implemented in this repository.

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

## Commands

Commands currently require permission level 2:

```text
/dynamicstage start <stage> <lod_pack> <source_x> <source_y> <source_z> [capacity]
/dynamicstage join <instance_uuid>
/dynamicstage anchor <source_x> <source_y> <source_z>
/dynamicstage exit
/dynamicstage flight import <stage> <name> [slot]
/dynamicstage flight status <stage>
/dynamicstage flight clear <stage>
```

`start` creates an instance, validates the local package, and then teleports. Once the target level wrapper exists, the client rebinds DH to the external database before flight playback begins; a failed rebind returns the player safely. `join` joins an active instance if capacity remains. `anchor` updates the shared virtual DH source position for every active or preparing member. `exit` restores the player's original dimension, position, and rotation.

## CMDCam and music

A validated CMDCam scene can be attached to a stage. Dynamic Stage sends the small scene JSON and a server game-time epoch; CMDCam runs the camera locally. Dynamic Stage supplies DH with `LOD anchor + CMDCam position delta`, while DH continues to render the LOD. Camera orientation and roll remain owned by the Minecraft/CMDCam camera pipeline.

Music remains the responsibility of `mob-battle-music`. KubeJS can combine its marker/state with `DynamicStageInstance` rather than requiring a second music protocol in Dynamic Stage.

## Development

Build and run tests:

```powershell
.\gradlew.bat clean test jarJar
```

The output is `build/libs/dynamicstage-0.1.0-all.jar`; it does not embed DH, CMDCam, SQLite, RocksDB, or compression libraries.

Run the DH compatibility client:

```powershell
.\gradlew.bat -PincludeDh=true runClient
```

Run the separate CMDCam compatibility pass:

```powershell
.\gradlew.bat -PincludeDh=true -PincludeCmdCam=true runClient
```

DH 3.2.0-b has been verified to initialize with both Dynamic Stage DH mixins on Forge 1.20.1. Loading an actual external package, switching packages in one connection, multiplayer timing, and CMDCam XYZ/yaw/pitch/roll still require in-world acceptance tests.

See [docs/REPOSITORY_REVIEW.md](docs/REPOSITORY_REVIEW.md) for the current architecture review and remaining work.
