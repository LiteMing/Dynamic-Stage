package vibe.liteming.dynamicstage.bake.voxy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.bake.BlockColorMapper;
import vibe.liteming.dynamicstage.bake.LodColumn;
import vibe.liteming.dynamicstage.bake.LodProvider;
import vibe.liteming.dynamicstage.bake.RegionSpec;
import vibe.liteming.dynamicstage.bake.ServerLevelRef;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Voxy LoD provider (VoxyFileProvider): reads a Voxy world's RocksDB database
 * and emits uniform {@link LodColumn}s, matching the bake pipeline output so the
 * pipeline is source-agnostic.
 * <p>
 * Coordinate-driven: only sections overlapping {@link RegionSpec} are read, and
 * each column's surface voxel is resolved through the id_mappings to a
 * {@link BlockState}, then to a colour via the shared {@link BlockColorMapper}.
 */
public class VoxyProvider implements LodProvider {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyProvider.class);

    public static final String ID = "voxy_file";
    public static final int FORMAT_VERSION = 1;

    private final java.nio.file.Path storageDir;
    private final BlockColorMapper colorMapper;

    /** blockId → BlockState (from id_mappings). */
    private final Map<Integer, BlockState> blockStates = new HashMap<>();

    public VoxyProvider(java.nio.file.Path storageDir, BlockColorMapper colorMapper) {
        this.storageDir = storageDir;
        this.colorMapper = colorMapper;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public boolean isAvailable(ServerLevelRef sourceWorld) {
        return java.nio.file.Files.exists(storageDir.resolve("CURRENT"));
    }

    @Override
    public int formatVersion() {
        return FORMAT_VERSION;
    }

    @Override
    public Stream<LodColumn> readRegion(RegionSpec spec) {
        VoxyRocksDB db = VoxyRocksDB.open(storageDir);
        if (db == null || !db.isOpen()) {
            return Stream.empty();
        }
        try {
            LOGGER.info("Voxy: reading mappings...");
            loadMappings(db);
            LOGGER.info("Voxy: mappings loaded ({})", blockStates.size());
            List<LodColumn> columns = new ArrayList<>();

            // Pass 1: collect section keys overlapping the spec (iterator-only).
            List<Long> candidates = new ArrayList<>();
            LOGGER.info("Voxy: iterating sections...");
            db.iterateSections(0, key -> {
                int sx = VoxySectionKey.xOf(key);
                int sz = VoxySectionKey.zOf(key);
                int size = VoxySectionKey.sectionSize(0); // 32
                long minX = (long) sx * size;
                long minZ = (long) sz * size;
                if (overlaps(spec, minX, minZ, minX + size, minZ + size)) {
                    candidates.add(key);
                }
            });
            LOGGER.info("Voxy: {} candidate sections for spec", candidates.size());

            // Pass 2: fetch + parse each candidate
            for (Long keyLong : candidates) {
                long key = keyLong;
                byte[] compressed = db.getSection(key);
                if (compressed == null) {
                    continue;
                }
                VoxySectionParser.parse(compressed, (bx, by, bz, blockId) -> {
                    if (!spec.contains((int) bx, (int) by, (int) bz)) {
                        return;
                    }
                    BlockState state = blockStates.get(blockId);
                    if (state == null || state.isAir()) {
                        return;
                    }
                    columns.add(new LodColumn((int) bx, (int) bz, (int) by - 1, (int) by,
                            colorMapper.colorFor(state), 0));
                });
            }

            if (!columns.isEmpty()) {
                columns.addAll(downsample(columns, 1));
                columns.addAll(downsample(columns, 2));
            }
            return columns.stream();
        } finally {
            db.close();
        }
    }

    private void loadMappings(VoxyRocksDB db) {
        for (Map.Entry<Integer, byte[]> entry : db.getIdMappings().entrySet()) {
            int key = entry.getKey();
            int type = key >>> 30;
            int id = key & ((1 << 30) - 1);
            if (type == VoxyRocksDB.BLOCK_STATE_TYPE) {
                BlockState state = parseBlockState(entry.getValue());
                if (state != null) {
                    blockStates.put(id, state);
                }
            }
        }
        LOGGER.info("Voxy: loaded {} block mappings", blockStates.size());
    }

    @Nullable
    private static BlockState parseBlockState(byte[] gzipNbt) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new ByteArrayInputStream(gzipNbt))))) {
            CompoundTag tag = NbtIo.read(in, new NbtAccounter(0x40000000L));
            if (tag == null || !tag.contains("block_state")) {
                return null;
            }
            CompoundTag bs = tag.getCompound("block_state");
            String name = bs.getString("Name");
            ResourceLocation id = ResourceLocation.tryParse(name);
            if (id == null) {
                return null;
            }
            Block block = ForgeRegistries.BLOCKS.getValue(id);
            return block == null ? null : block.defaultBlockState();
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean overlaps(RegionSpec spec, long minX, long minZ, long maxX, long maxZ) {
        for (long x = minX; x < maxX; x += 16) {
            for (long z = minZ; z < maxZ; z += 16) {
                if (spec.contains((int) x, 64, (int) z)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<LodColumn> downsample(List<LodColumn> base, int level) {
        int factor = 1 << level;
        Map<Long, int[]> cellColors = new HashMap<>();
        Map<Long, int[]> cellRanges = new HashMap<>();
        for (LodColumn col : base) {
            if (((col.colorArgb() >>> 24) & 0xFF) < 255) {
                continue; // skip translucent (water) in higher LoD
            }
            long cellKey = ((long) Math.floorDiv(col.x(), factor) << 32) | (Math.floorDiv(col.z(), factor) & 0xFFFFFFFFL);
            int[] acc = cellColors.computeIfAbsent(cellKey, k -> new int[4]);
            acc[0] += (col.colorArgb() >> 16) & 0xFF;
            acc[1] += (col.colorArgb() >> 8) & 0xFF;
            acc[2] += col.colorArgb() & 0xFF;
            acc[3]++;
            int[] range = cellRanges.computeIfAbsent(cellKey, k -> new int[]{Integer.MAX_VALUE, Integer.MIN_VALUE});
            range[0] = Math.min(range[0], col.yStart());
            range[1] = Math.max(range[1], col.yEnd());
        }
        List<LodColumn> result = new ArrayList<>(cellColors.size());
        cellColors.forEach((cellKey, acc) -> {
            int count = Math.max(1, acc[3]);
            int r = acc[0] / count;
            int g = acc[1] / count;
            int b = acc[2] / count;
            int[] range = cellRanges.get(cellKey);
            int cellX = (int) (cellKey >> 32);
            int cellZ = (int) (cellKey & 0xFFFFFFFFL);
            result.add(new LodColumn(cellX * factor, cellZ * factor, range[0], range[1],
                    0xFF000000 | (r << 16) | (g << 8) | b, level));
        });
        return result;
    }
}
