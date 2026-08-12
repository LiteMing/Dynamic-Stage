package vibe.liteming.dynamicstage.bake.voxy;

import com.github.luben.zstd.Zstd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Parses Voxy section payloads (SaveLoadSystem3 format):
 * <pre>
 * [sectionKey 8B][metadata 8B][index 32³×2B LE short][LUT N×8B LE long]
 * </pre>
 * metadata low 16 bits = LUT size; voxel long = blockId<<27 | biomeId<<47 | light<<56.
 * <p>
 * Extracts, per column (x,z within the 32³ section), the highest non-air voxel
 * as the surface, matching the bake pipeline's column semantics.
 */
public final class VoxySectionParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoxySectionParser.class);
    private static final int VOLUME = 32 * 32 * 32;

    private VoxySectionParser() {
    }

    /**
     * Decompresses and parses a section payload, invoking the consumer for every
     * column that has at least one non-air block.
     *
     * @param compressed the raw ZSTD-compressed payload from world_sections
     * @param consumer   receives (blockX, blockY, blockZ, blockId)
     */
    public static void parse(byte[] compressed, ColumnConsumer consumer) {
        long decompressedSize = Zstd.decompressedSize(compressed);
        if (decompressedSize <= 0 || decompressedSize > 32 * 1024 * 1024) {
            return;
        }
        byte[] raw = new byte[(int) decompressedSize];
        long actual = Zstd.decompress(raw, compressed);
        if (Zstd.isError(actual)) {
            LOGGER.warn("Voxy section decompress failed: {}", Zstd.getErrorName(actual));
            return;
        }
        if (actual < 16) {
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        long key = buf.getLong();                 // 0..7
        long metadata = buf.getLong();            // 8..15
        int lutSize = (int) (metadata & 0xFFFF);
        if (lutSize < 0 || lutSize > 0xFFFF) {
            return;
        }
        if (raw.length < 16 + VOLUME * 2L) {
            return;
        }
        int lutOffset = 16 + VOLUME * 2;

        int[] lut = new int[lutSize];
        for (int i = 0; i < lutSize; i++) {
            lut[i] = buf.getInt(lutOffset + i * 8);   // low 32 bits of voxel long
        }

        int x0 = VoxySectionKey.xOf(key);
        int y0 = VoxySectionKey.yOf(key);
        int z0 = VoxySectionKey.zOf(key);

        int[] surface = new int[32 * 32];
        java.util.Arrays.fill(surface, -1);
        for (int i = 0; i < VOLUME; i++) {
            int palIdx = Short.toUnsignedInt(buf.getShort(16 + i * 2));
            if (palIdx >= lutSize) {
                continue;
            }
            int voxelLow = lut[palIdx];
            int blockId = (voxelLow >> 27) & 0xFFFFF;
            if (blockId == 0) {
                continue;
            }
            int x = i & 0x1F;
            int y = (i >> 5) & 0x1F;
            int z = (i >> 10) & 0x1F;
            int col = x + z * 32;
            if (y > surface[col]) {
                surface[col] = y;
            }
        }

        for (int col = 0; col < 32 * 32; col++) {
            if (surface[col] < 0) {
                continue;
            }
            int x = col & 0x1F;
            int z = col >> 5;
            int index = x | (surface[col] << 5) | (z << 10);
            int palIdx = Short.toUnsignedInt(buf.getShort(16 + index * 2));
            int voxelLow = lut[palIdx];
            int blockId = (voxelLow >> 27) & 0xFFFFF;
            consumer.accept(
                    (long) x0 * VoxySectionKey.sectionSize(VoxySectionKey.levelOf(key)) + x,
                    (long) y0 * VoxySectionKey.sectionSize(VoxySectionKey.levelOf(key)) + surface[col],
                    (long) z0 * VoxySectionKey.sectionSize(VoxySectionKey.levelOf(key)) + z,
                    blockId);
        }
    }

    /** Callback receiving per-column surface blocks. */
    public interface ColumnConsumer {
        void accept(long blockX, long blockY, long blockZ, int blockId);
    }

    /** Callback receiving every non-air voxel of a section. */
    public interface VoxelConsumer {
        void accept(long blockX, long blockY, long blockZ, int blockId);
    }

    /**
     * Decompresses and parses a section payload, invoking the consumer for every
     * non-air voxel. Used by the direct Voxy renderer, which builds its mesh from
     * the raw voxel data (no column reconstruction).
     */
    public static void parseAll(byte[] compressed, VoxelConsumer consumer) {
        long decompressedSize = Zstd.decompressedSize(compressed);
        if (decompressedSize <= 0 || decompressedSize > 32 * 1024 * 1024) {
            return;
        }
        byte[] raw = new byte[(int) decompressedSize];
        long actual = Zstd.decompress(raw, compressed);
        if (Zstd.isError(actual)) {
            LOGGER.warn("Voxy section decompress failed: {}", Zstd.getErrorName(actual));
            return;
        }
        if (actual < 16) {
            return;
        }

        ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        long key = buf.getLong();                 // 0..7
        long metadata = buf.getLong();            // 8..15
        int lutSize = (int) (metadata & 0xFFFF);
        if (lutSize < 0 || lutSize > 0xFFFF) {
            return;
        }
        if (raw.length < 16 + VOLUME * 2L) {
            return;
        }
        int lutOffset = 16 + VOLUME * 2;

        int[] lut = new int[lutSize];
        for (int i = 0; i < lutSize; i++) {
            lut[i] = buf.getInt(lutOffset + i * 8);   // low 32 bits of voxel long
        }

        int x0 = VoxySectionKey.xOf(key);
        int y0 = VoxySectionKey.yOf(key);
        int z0 = VoxySectionKey.zOf(key);
        int size = VoxySectionKey.sectionSize(VoxySectionKey.levelOf(key));

        for (int i = 0; i < VOLUME; i++) {
            int palIdx = Short.toUnsignedInt(buf.getShort(16 + i * 2));
            if (palIdx >= lutSize) {
                continue;
            }
            int voxelLow = lut[palIdx];
            int blockId = (voxelLow >> 27) & 0xFFFFF;
            if (blockId == 0) {
                continue;
            }
            int x = i & 0x1F;
            int y = (i >> 5) & 0x1F;
            int z = (i >> 10) & 0x1F;
            consumer.accept((long) x0 * size + x, (long) y0 * size + y, (long) z0 * size + z, blockId);
        }
    }
}
