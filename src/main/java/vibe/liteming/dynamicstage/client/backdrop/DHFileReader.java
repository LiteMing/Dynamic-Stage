package vibe.liteming.dynamicstage.client.backdrop;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.bake.anvil.MapColorMapper;

import javax.annotation.Nullable;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jpountz.lz4.LZ4FrameInputStream;

/**
 * Reads a Distant Horizons {@code FullData} SQLite database directly into
 * voxels for the renderer (forge-side LOD source, same no-bake flow as Voxy).
 * <p>
 * Format (per distant-horizons-rust + DH 2.x schema):
 * <ul>
 *   <li>{@code FullData(DetailLevel, PosX, PosZ, MinY, Data, Mapping, ...)} —
 *       DetailLevel stored = actual − 6 (Chunk4+); PosX/Z are section grid
 *       coordinates; section covers {@code 2^level} blocks.</li>
 *   <li>{@code Data} — compressed according to {@code CompressionMode};
 *       decompressed = 64×64 columns,
 *       each {@code [u16 BE len] + len×8B DataPoint} where DataPoint =
 *       {@code meta(u32 BE) | id(u32 BE)}, meta = height(12) | minY(12) |
 *       skyLight(4) | blockLight(4). Each DataPoint is a run of blocks
 *       {@code [minY, minY+height)} with block id.</li>
 *   <li>{@code Mapping} — same compression mode; {@code [u32 BE count] +
 *       count× Java readUTF} strings
 *       {@code biome_DH-BSW_block_STATE_{key:value}...}.</li>
 * </ul>
 */
public final class DHFileReader {

    private static final Logger LOGGER = LoggerFactory.getLogger(DHFileReader.class);

    public static final String SEPARATOR = "_DH-BSW_";
    public static final int SECTION_MINIMUM_DETAIL_LEVEL = 6; // Chunk4
    public static final int SECTION_COLUMNS = 64;

    /** Hard cap on total voxels fed to the renderer. */
    public static final long VOXEL_BUDGET = 2_000_000L;

    private DHFileReader() {
    }

    /**
     * Reads all voxels within the LOD radius of the anchor.
     * May run on a background thread (file IO + native decompression).
     */
    public static List<Voxel> read(Path sqlite, BlockPos anchor) {
        if (!Files.exists(sqlite)) {
            return List.of();
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            LOGGER.warn("sqlite-jdbc not available: {}", e.getMessage());
            return List.of();
        }
        List<Voxel> out = new ArrayList<>();
        MapColorMapper colorMapper = new MapColorMapper();
        // Detail levels: 6 (Chunk4, 64 blocks, 1 block/col) → 8 (Chunk16).
        for (int level = SECTION_MINIMUM_DETAIL_LEVEL; level <= 8 && out.size() < VOXEL_BUDGET; level++) {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + sqlite.toAbsolutePath())) {
                int ringIndex = level - SECTION_MINIMUM_DETAIL_LEVEL;
                int minDistance = ringIndex == 0 ? 0 : 96 << (ringIndex - 1);
                int maxDistance = 96 << ringIndex;
                readLevel(conn, level, minDistance, maxDistance, anchor, colorMapper, out);
            } catch (Exception e) {
                LOGGER.warn("DH read level {} failed: {}", level, e.toString());
            }
        }
        return out;
    }

    private static void readLevel(Connection conn, int level, int minDistance, int maxDistance, BlockPos anchor,
                                  MapColorMapper colorMapper, List<Voxel> out) throws Exception {
        int blockWidth = 1 << level;                    // blocks per section side
        int colSize = blockWidth / SECTION_COLUMNS;     // blocks per column cell
        String sql = "SELECT PosX, PosZ, MinY, Data, Mapping, CompressionMode, DataFormatVersion FROM FullData WHERE DetailLevel = " + (level - SECTION_MINIMUM_DETAIL_LEVEL);
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next() && out.size() < VOXEL_BUDGET) {
                int sectionX = rs.getInt("PosX");
                int sectionZ = rs.getInt("PosZ");
                int minBlockX = sectionX * blockWidth;
                int minBlockZ = sectionZ * blockWidth;
                double centerX = minBlockX + blockWidth / 2.0D;
                double centerZ = minBlockZ + blockWidth / 2.0D;
                int minY = rs.getInt("MinY");
                double dx = centerX - anchor.getX();
                double dz = centerZ - anchor.getZ();
                double distance = Math.sqrt(dx * dx + dz * dz);
                if (distance > maxDistance || distance <= minDistance) {
                    continue;
                }
                byte[] data = rs.getBytes("Data");
                byte[] mapping = rs.getBytes("Mapping");
                int compressionMode = rs.getInt("CompressionMode");
                int formatVersion = rs.getInt("DataFormatVersion");
                if (formatVersion != 1) {
                    LOGGER.debug("Skipping unsupported DH data format {} at {},{}", formatVersion, sectionX, sectionZ);
                    continue;
                }
                if (data == null || mapping == null) {
                    continue;
                }
                List<String> entries = parseMapping(decompress(mapping, compressionMode));
                Map<Integer, BlockState> localStates = resolveStates(entries);
                parseColumns(decompress(data, compressionMode), minBlockX, minBlockZ, minY, colSize,
                        localStates, colorMapper, out);
            }
        }
    }

    /** 64×64 columns: [u16 BE len] + len × 8B DataPoint. */
    private static void parseColumns(byte[] raw, int minBlockX, int minBlockZ, int minY, int colSize,
                                     Map<Integer, BlockState> states, MapColorMapper colorMapper,
                                     List<Voxel> out) {
        if (raw == null || raw.length < 2) {
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        int offset = 0;
        for (int col = 0; col < SECTION_COLUMNS * SECTION_COLUMNS && offset + 2 <= raw.length; col++) {
            int len = buf.getShort(offset) & 0xFFFF;
            offset += 2;
            if (len < 0 || len > 256 || offset + len * 8L > raw.length) {
                break;
            }
            int colX = col % SECTION_COLUMNS;
            int colZ = col / SECTION_COLUMNS;
            int blockX = minBlockX + colX * colSize;
            int blockZ = minBlockZ + colZ * colSize;
            for (int i = 0; i < len; i++) {
                long meta = buf.getInt(offset) & 0xFFFFFFFFL;
                long id = buf.getInt(offset + 4) & 0xFFFFFFFFL;
                offset += 8;
                int height = (int) (meta & 0xFFF);
                int colMinY = (int) ((meta >> 12) & 0xFFF) + minY;
                if (height <= 0) {
                    continue;
                }
                BlockState state = states.get((int) id);
                if (state == null || state.isAir()) {
                    continue;
                }
                int color = colorMapper.colorFor(state);
                if (colSize <= 1) {
                    for (int y = colMinY; y < colMinY + height && out.size() < VOXEL_BUDGET; y++) {
                        out.add(new Voxel(blockX, y, blockZ, color));
                    }
                } else {
                    // Coarse level: emit a single voxel of the column-cell size.
                    out.add(new Voxel(blockX, colMinY, blockZ, color, colSize, height));
                }
            }
        }
    }

    /** Mapping: [u32 BE count] + count × Java readUTF strings. */
    private static List<String> parseMapping(byte[] raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.length < 4) {
            return out;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            int count = in.readInt();
            for (int i = 0; i < count && i < 65536; i++) {
                out.add(in.readUTF());
            }
        } catch (IOException e) {
            // partial mapping is acceptable
        }
        return out;
    }

    private static Map<Integer, BlockState> resolveStates(List<String> entries) {
        Map<Integer, BlockState> map = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            String entry = entries.get(i);
            int sep = entry.indexOf(SEPARATOR);
            if (sep < 0) {
                continue;
            }
            String blockState = entry.substring(sep + SEPARATOR.length());
            String blockName = blockState;
            int stateIdx = blockState.indexOf("_STATE_");
            if (stateIdx >= 0) {
                blockName = blockState.substring(0, stateIdx);
            }
            ResourceLocation id = ResourceLocation.tryParse(blockName);
            if (id == null) {
                continue;
            }
            Block block = ForgeRegistries.BLOCKS.getValue(id);
            if (block != null) {
                map.put(i, block.defaultBlockState());
            }
        }
        return map;
    }

    /**
     * Decompresses an LZMA2 (XZ) blob. Some DH streams lack the end-of-stream
     * marker; read what we can and keep the partial output.
     */
    @Nullable
    private static byte[] decompress(byte[] compressed, int compressionMode) {
        if (compressed == null) {
            return null;
        }
        try {
            if (compressionMode == 0) {
                return compressed;
            }
            java.io.InputStream in;
            if (compressionMode == 1) {
                in = new LZ4FrameInputStream(new ByteArrayInputStream(compressed));
            } else if (compressionMode == 3) {
                in = new org.tukaani.xz.XZInputStream(new ByteArrayInputStream(compressed));
            } else {
                LOGGER.warn("Unsupported DH compression mode {}", compressionMode);
                return null;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            } catch (EOFException ignored) {
                // truncated XZ stream — keep partial data
            } catch (IOException e) {
                // truncated XZ stream — keep partial data
            }
            return out.toByteArray();
        } catch (IOException e) {
            LOGGER.warn("DH decompress mode {} failed: {}", compressionMode, e.getMessage());
            return null;
        }
    }
}
