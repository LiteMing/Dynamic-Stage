# Dynamic Stage

Dynamic Stage is an early Forge 1.20.1 prototype for isolated stage dimensions backed by client-rendered LOD scenery. It currently reads Voxy RocksDB or Distant Horizons SQLite data, creates a GPU voxel mesh, and can apply a CMDCam playback pose as an inverse backdrop transform.

The repository is currently a single Forge module. Architectury and Fabric loaders have not been wired in yet, and the direct-file workflow is intended for integrated-client development rather than remote multiplayer.

## Development commands

Run tests and build the distributable Jar-in-Jar artifact:

```powershell
.\gradlew.bat clean test jarJar
```

The output is `build/libs/dynamicstage-0.1.0-all.jar`. RocksDB, Zstd, SQLite JDBC, LZ4, and XZ are embedded in that artifact.

Start a Forge development client:

```powershell
.\gradlew.bat runClient
```

CMDCam and CreativeCore are development runtime dependencies. To test Distant Horizons itself, place a compatible DH jar under `run/mods/`.

## Current stage flow

The commands require permission level 2:

```text
/dynamicstage start <stage> dim <x> <y> <z> [voxy|dh]
/dynamicstage exit
```

`start` locates the selected LOD store in the current save, records the source anchor, and teleports the player into `dynamicstage:stg_stage`. The client reads the LOD data asynchronously and renders it as non-interactive scenery. `exit` currently returns to the overworld shared spawn, not the player's exact pre-stage position.

See [docs/REPOSITORY_REVIEW.md](docs/REPOSITORY_REVIEW.md) for implementation status, known limitations, and the recommended work order.
