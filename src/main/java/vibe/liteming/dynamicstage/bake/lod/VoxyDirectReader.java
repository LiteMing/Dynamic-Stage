package vibe.liteming.dynamicstage.bake.lod;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import vibe.liteming.dynamicstage.bake.anvil.MapColorMapper;
import vibe.liteming.dynamicstage.bake.voxy.VoxyMappings;
import vibe.liteming.dynamicstage.bake.voxy.VoxyRocksDB;
import vibe.liteming.dynamicstage.bake.voxy.VoxySectionKey;
import vibe.liteming.dynamicstage.bake.voxy.VoxySectionParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a Voxy RocksDB database into portable bake voxels, using
 * Voxy's native LoD levels (32/64/128-block sections) selected by distance.
 * The server-side baker serialises the result into a distributed {@code .sdb}.
 */
public final class VoxyDirectReader {

    /** Level 0 sections cover this radius around the anchor. */
    public static final int L0_RADIUS = 96;
    /** Level 1 sections cover up to this radius. */
    public static final int L1_RADIUS = 192;
    /** Level 2 sections cover up to this radius. */
    public static final int L2_RADIUS = 384;

    /** Hard cap on total voxels fed to the renderer (GPU mesh budget). */
    public static final long VOXEL_BUDGET = 2_000_000L;

    private VoxyDirectReader() {
    }

    /**
     * Reads all non-air voxels within the LOD radius of the anchor.
     * May run on a background thread (pure file IO + native decompression).
     * Higher LoD levels shrink when the voxel budget is exhausted.
     *
     * @return voxels sorted by level, or an empty list on failure
     */
    public static List<Voxel> read(java.nio.file.Path storageDir, BlockPos anchor) {
        VoxyRocksDB db = VoxyRocksDB.open(storageDir);
        if (db == null || !db.isOpen()) {
            return List.of();
        }
        try {
            Map<Integer, BlockState> states = VoxyMappings.load(db);
            MapColorMapper colorMapper = new MapColorMapper();
            List<Voxel> out = new ArrayList<>();
            // Voxy native LoD: nearer sections use finer levels; higher levels
            // shrink as the budget is consumed.
            readLevel(db, states, colorMapper, anchor, 0, 0, L0_RADIUS, VOXEL_BUDGET, out);
            long used = out.size();
            int r1 = (int) Math.round(L0_RADIUS + (L1_RADIUS - L0_RADIUS)
                    * Math.max(0.0D, 1.0D - used / (double) VOXEL_BUDGET));
            readLevel(db, states, colorMapper, anchor, 1, L0_RADIUS, r1, VOXEL_BUDGET - used, out);
            used = out.size();
            int r2 = (int) Math.round(L1_RADIUS + (L2_RADIUS - L1_RADIUS)
                    * Math.max(0.0D, 1.0D - used / (double) VOXEL_BUDGET));
            readLevel(db, states, colorMapper, anchor, 2, r1, r2, VOXEL_BUDGET - used, out);
            return out;
        } finally {
            db.close();
        }
    }

    private static void readLevel(VoxyRocksDB db, Map<Integer, BlockState> states, MapColorMapper colorMapper,
                                  BlockPos anchor, int level, int minDist, int maxDist, long budget, List<Voxel> out) {
        if (budget <= 0) {
            return;
        }
        long limit = Math.min(VOXEL_BUDGET, out.size() + budget);
        int size = VoxySectionKey.sectionSize(level);
        int cellSize = 1 << level;
        db.iterateSectionsWhile(level, key -> {
            if (out.size() >= limit) {
                return false;
            }
            int sx = VoxySectionKey.xOf(key);
            int sz = VoxySectionKey.zOf(key);
            double cx = (sx + 0.5D) * size;
            double cz = (sz + 0.5D) * size;
            double dx = cx - anchor.getX();
            double dz = cz - anchor.getZ();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist > maxDist || (minDist > 0 && dist <= minDist)) {
                return true;
            }
            byte[] compressed = db.getSection(key);
            if (compressed == null) {
                return true;
            }
            VoxySectionParser.parseAll(compressed, (bx, by, bz, blockId) -> {
                if (out.size() >= limit) {
                    return;
                }
                BlockState state = states.get(blockId);
                if (state == null || state.isAir()) {
                    return;
                }
                out.add(new Voxel((int) bx, (int) by, (int) bz, colorMapper.colorFor(state),
                        cellSize, cellSize));
            });
            return out.size() < limit;
        });
    }
}
