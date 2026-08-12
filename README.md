# Dynamic Stage

Dynamic Stage is a Forge 1.20.1 prototype for per-player isolated stage regions backed by client-rendered LOD scenery. The server reads Voxy RocksDB or Distant Horizons SQLite data into a portable `.sdb`, distributes it in validated 16 KiB chunks, and the client caches it by SHA-256 before creating a GPU voxel mesh. A live CMDCam playback pose can be applied as an inverse backdrop transform.

The repository is currently a single Forge module. Architectury and Fabric loaders have not been wired in yet. The backdrop protocol supports remote clients, but multiplayer gameplay and visual behavior still need in-game acceptance testing.

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

`start` locates the selected LOD store, prepares or reuses a content-addressed backdrop on one background worker, then teleports the player into an isolated region of `dynamicstage:stg_stage`. The client downloads/cache-checks the Blob and renders it as non-interactive scenery. `exit` restores the exact pre-stage dimension, position, and rotation. Running `exit` while preparation is in progress cancels that player's pending entry.

Server products are stored under the save's `data/dynamicstage/backdrops/`; client products are stored under `.minecraft/dynamicstage_cache/`. Stage identifiers are hashed before they become path components.

See [docs/REPOSITORY_REVIEW.md](docs/REPOSITORY_REVIEW.md) for implementation status, known limitations, and the recommended work order.
