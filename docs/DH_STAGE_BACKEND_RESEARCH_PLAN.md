# Dynamic Stage: Distant Horizons stage backend research plan

Status: stock-DH compatibility implementation active; a DH fork remains deferred
Target game: Minecraft 1.20.1 Forge  
Reference backend: Distant Horizons 3.2.0-b  
License boundary: Dynamic Stage remains MIT; modified Distant Horizons code remains LGPL-3.0

## 1. Purpose

Dynamic Stage needs a legally distributable LOD backend for cropped, static,
non-interactive stage backgrounds. Stock Distant Horizons is currently behind
the Voxy reference in visual quality, frame time, and package size, so merely
making DH the default backend is not an acceptable result.

This research will determine whether access to the LGPL-licensed DH source lets
us build a narrowly scoped stage mode which closes enough of that gap. It will
not assume success. Every prototype is measured against the same scene and
camera traces, and each phase has a stop condition.

Voxy may be used only as a black-box visual and performance reference. No Voxy
source, shader, storage implementation, or translated code may be copied into
the DH work.

## 2. Existing foundation

Dynamic Stage already has a working DH 3.2 integration which:

- mounts an external `DistantHorizons.sqlite` through DH's save-structure
  override;
- verifies that DH opened the selected package instead of the stage world's
  normal cache;
- enables DH read-only mode while the stage is active;
- unloads and reloads the DH client/server wrappers when a package changes;
- maps the stage camera to an independent source anchor;
- applies Flight position, yaw, pitch, roll, and FOV locally;
- adjusts the vanilla-terrain handoff distance;
- filters or suppresses DH's final LOD composite for stage transitions.

The current implementation reaches DH internals through reflection and Mixins.
The research backend should turn the stable subset into explicit stage APIs and
delete reflection only after the replacement is proven.

## 3. Constraints

### Required behavior

- The server does not run DH, generate LODs, stream terrain, or send per-frame
  camera state.
- A client mounts at most one background package for its current stage
  instance.
- Stage coordinates, source LOD coordinates, and Flight coordinates remain
  independent.
- The player can move normally inside the physical stage while the virtual LOD
  camera follows the configured scale or stays pinned.
- Stage LODs are read-only and non-interactive.
- Package switching, reconnect, world exit, and client shutdown release all
  database, thread, and GPU resources.
- The result continues to use the native DH renderer. Dynamic Stage does not
  become a second LOD renderer.

### Non-goals for the first research cycle

- Improving normal Overworld DH rendering.
- General-purpose world generation or multiplayer DH streaming.
- Supporting every DH database version.
- Shader-pack parity before the non-shader renderer is accepted.
- Interactive collision against LOD geometry.
- Reproducing Voxy's storage format or algorithms.

## 4. Reference workloads

Benchmarks must use immutable local copies and record SHA-256 hashes. Map or LOD
assets are not committed to the public repository.

1. `sparse-small`: a small structure with large empty surroundings.
2. `mansion-garden`: the existing large building, garden, trees, water, and
   transparent foliage reference scene.
3. `dense-vertical`: a deliberately hostile scene with caves, stacked rooms,
   fluids, and a large vertical range.
4. `foliage-water`: a smaller scene focused on alpha-tested blocks, transparent
   blocks, and water surfaces.

Each workload uses the same X/Z crop, Y policy, source anchor, stage boundary,
render distance, graphics settings, and recorded camera trace for every
backend. A compacted Voxy package can be measured as a black box, but its
contents are not inspected during DH development.

## 5. Measurements and provisional gates

Phase 0 records the baseline before implementation. The baseline report freezes
the exact thresholds used by later phases so they cannot be relaxed after a
prototype performs poorly.

### Storage

Record:

- source database, optimized database, and final `.dstlod` archive sizes;
- SQLite `page_count`, `freelist_count`, page size, and WAL size;
- row count and byte totals by DH detail level;
- byte totals for `Data`, `Mapping`, `ColumnGenerationStep`, and
  `ColumnWorldCompressionMode`;
- decoded vertical segment counts per X/Z column;
- crop bounds and retained visible surface count.

Provisional acceptance for `mansion-garden`:

- hard ceiling: the distributable archive is at most 100 MiB;
- target: at most 25 MiB or four times the equivalent compacted Voxy package,
  whichever is larger;
- optimization never edits the source database in place;
- a second optimization with identical inputs produces byte-identical output.

### Runtime

Record cold and warm mount time, time to first stable LOD frame, incremental
heap/native/VRAM usage, background CPU time, GPU frame time, draw calls,
database reads, and p50/p95/p99 frame time during:

- a stationary camera;
- bounded player movement;
- a looping Flight with translation and rotation;
- a 360-degree camera sweep;
- package switch and stage exit.

Provisional acceptance:

- no sustained server work or terrain packets after the small DS session state;
- no DH world-generation, chunk ingestion, remote retrieval, or database-write
  worker remains active in stage mode;
- no background I/O stall longer than 50 ms after declared preload completion;
- stage entry reaches a stable local-SSD frame within 3 seconds for the
  reference package;
- p95 frame-time overhead is no more than 3 ms over the same stage without a
  LOD backend at the selected reference distance.

Hardware-specific numbers are reported with CPU, GPU, driver, JVM, and shader
state. A result from one machine is not presented as universal.

### Visual correctness

- no camera-adjacent holes while the player remains inside the stage boundary;
- no LOD section flicker when crossing source-section boundaries;
- no distant Z-fighting introduced by the stage near/far projection;
- Flight translation and yaw/pitch/roll/FOV match the recorded trace;
- first- and third-person camera offsets do not move the source anchor;
- trees, fences, glass, and water preserve recognizable silhouettes;
- crop edges remain outside all approved stage camera paths;
- normal-world DH behavior is unchanged when stage mode is inactive.

Image comparison uses fixed screenshots and camera poses. Pixel metrics are
reported alongside human inspection because foliage and temporal effects make
a single similarity score misleading.

## 6. Research questions

### Storage anatomy

DH `FullDataSourceV2` stores rows keyed by detail level and X/Z section. Each row
contains compressed vertical data, a local biome/block mapping, column
generation state, and world-compression state. The investigation must answer:

1. How much space is actual vertical geometry versus repeated mappings and
   metadata?
2. How much underground/cave data survives in a scene that only needs exterior
   surfaces?
3. How much data lies outside the stage camera envelope?
4. How much is obsolete SQLite free space or WAL state?
5. Are multiple detail levels storing overlapping information which can be
   regenerated after cropping?
6. Is compressed full data smaller or larger than a precomputed, portable
   render-column cache for a static scene?

The inspector must use SQLite metadata and DH's structured DTO/data-source APIs.
It must not parse compressed blobs with ad hoc byte offsets.

### Static stage rendering

Stock DH assumes a moving player, nearby vanilla chunks, live generation, and
possibly multiplayer data retrieval. A stage backdrop has different invariants:

- source data is complete and immutable for the duration of a scene;
- the valid camera envelope is known before distribution;
- the physical world contains no corresponding source chunks;
- preload is preferable to continuous generation and eviction;
- near-terrain handoff must be optional;
- frustum culling remains useful, but player/chunk proximity suppression does
  not.

The renderer study must locate the minimum set of DH services that can be
disabled without replacing its quadtree, render-column conversion, buffers,
shaders, or final depth-aware composition.

## 7. Work packages

### Phase 0: Reproducible baseline

1. Create separate worktrees; do not alter the existing DH checkout or DS main
   branch.
2. Pin DH tag `3.2.0b`, Forge 1.20.1, Java 17, DS commit, and driver versions.
3. Capture one deterministic camera/Flight trace per workload.
4. Record stock DH, current DS+DH, and black-box Voxy reference results.
5. Publish the measurement method and raw CSV/JSON, excluding copyrighted map
   assets and LOD packages.

Gate A: stop if the current DH renderer cannot produce acceptable source-scene
geometry even after known near-fade and virtual-camera corrections.

### Phase 1: Read-only DH database inspector

Build a command-line research tool which reports:

- schema and DH format version;
- spatial/detail-level coverage;
- compressed BLOB sizes by field and section;
- decoded vertical segment and mapping cardinality histograms;
- estimated contribution of underground segments and out-of-envelope rows;
- SQLite free-page and WAL overhead.

The tool opens a copy read-only, has no migration path, and emits a machine
readable report. This phase changes no renderer code.

Gate B: stop storage work if meaningful space is dominated by exterior surface
data which cannot be removed without visible loss and the 100 MiB hard ceiling
is not plausible.

### Phase 2: Offline stage pack optimizer

Produce a new database through DH's own DTO/data-source encoders:

1. crop rows against an X/Z envelope derived from the source anchor and all
   approved camera paths;
2. apply a configurable Y range;
3. test `top`, `shell`, and `range` retention policies;
4. preserve exterior-facing opaque surfaces, water, foliage, glass, and other
   selected transparent layers;
5. remove inaccessible underground transitions for exterior-only scenes;
6. regenerate required parent/detail data from the retained finest data;
7. clear update-propagation flags for immutable packages;
8. use `VACUUM INTO` or an equivalent new-file compaction after clean shutdown;
9. validate by reopening through the pinned DH build and rendering every fixed
   camera pose.

An output manifest records the source hash, optimizer version, crop, retention
policy, DH data version, and output hash. Failed output is deleted; source input
is never overwritten.

Gate C: continue only if the optimized reference archive meets the hard storage
ceiling without visible holes or unacceptable foliage/water loss.

### Phase 3: Explicit DH stage mode

Add a narrow LGPL stage API to the DH fork. The provisional interface owns:

- mounting an explicit database path;
- immutable/read-only operation;
- stage camera position, look vector, model-view, projection, and FOV;
- preload envelope and completion state;
- near-terrain handoff and stage culling policy;
- resource statistics and deterministic unmount.

Stage mode disables generation, chunk ingestion, remote full-data requests,
save queues, parent/child update propagation, and normal-world cache discovery.
It retains frustum culling, render-column generation, GPU buffer management,
and DH's final depth-aware composition.

Dynamic Stage calls this API only on the client. The fork never receives a
per-frame network packet; it reads the local DS camera snapshot.

Gate D: stop if stage mode requires a broad renderer rewrite rather than an
isolated lifecycle/data-source path, or if it regresses normal DH while inactive.

### Phase 4: DS adapter migration

After the API is stable:

1. add version/capability negotiation;
2. prefer the explicit API and retain current reflection only as a temporary
   stock-DH fallback;
3. migrate mount, camera, preload, near handoff, and unmount one capability at a
   time;
4. retain DS transitions and filters in the DS compositor;
5. test reconnect, live package switch, multiple server instances, and shutdown;
6. remove a reflection/Mixin path only after its API replacement has an
   integration test.

### Phase 5: Shader compatibility and release decision

Test Oculus only after the non-shader path passes Gates A-D. Record shader-pack
specific failures separately from core DH failures. The first distributable
prototype may declare shader mode unsupported rather than delaying storage and
lifecycle validation.

The final report chooses one outcome:

- ship a maintained LGPL DH stage fork;
- upstream the generic API and keep only a small DS adapter;
- retain stock DH as a fallback but reject it as the primary backend;
- stop DH work and design a new independently implemented stage LOD backend.

## 8. Repository and license boundaries

- Dynamic Stage code and protocol remain in `LiteMing/Dynamic-Stage` under MIT.
- DH-derived changes live in a separate public fork under LGPL-3.0.
- Modified DH files retain upstream headers and clearly identify changes.
- Distributed DH binaries link to the exact corresponding source commit and
  include the LGPL/GPL license texts required by upstream.
- Benchmark scripts and original analysis may be MIT, but code copied or
  modified from DH remains LGPL.
- Voxy is never a source dependency for the DH fork. Only measured public
  behavior and output metrics are used for comparison.
- Reference maps, caches, music, and other user content are not committed or
  relicensed.

## 9. Risks

- Surface filtering may expose holes from camera paths not included in the
  optimization envelope.
- Transparent and liquid layers need semantic retention rather than a simple
  top-block rule.
- Regenerating parent detail levels incorrectly can preserve removed cave data
  or create seams.
- DH's internal API and SQLite format are version-sensitive; the first release
  must pin 3.2.x rather than claim broad compatibility.
- SQLite read-only flags do not guarantee that upstream code will never request
  a write; stage mode must prevent write services from starting.
- Preloading an entire cropped scene can trade I/O stutter for excessive RAM or
  VRAM, so both must be measured.
- Shader forks may bypass the normal DH matrices or depth composition.
- A large patch delta from upstream increases security and maintenance cost
  even when runtime performance is acceptable.

## 10. Initial deliverables

The research cycle is complete only when it produces:

1. a reproducible benchmark protocol;
2. baseline metrics for all four workloads;
3. a read-only DH database inspection report;
4. at least one optimized database generated into a new path;
5. fixed-pose screenshot and camera-trace comparisons;
6. CPU, GPU, memory, I/O, and package-size results;
7. a stage-mode API prototype or a documented Gate D rejection;
8. a go/no-go report with the exact source commits and license obligations.

No production default is changed merely because a prototype renders. The DH
route resumes only after it passes the storage, runtime, visual, lifecycle, and
license gates together.
