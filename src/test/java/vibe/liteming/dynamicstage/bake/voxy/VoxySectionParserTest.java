package vibe.liteming.dynamicstage.bake.voxy;

import com.github.luben.zstd.Zstd;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoxySectionParserTest {

    @Test
    void scalesCellCoordinatesAtHigherLodLevels() {
        int level = 2;
        long key = VoxySectionKey.encode(level, 1, -1, -2);
        byte[] raw = ByteBuffer.allocate(16 + 32 * 32 * 32 * 2 + 16)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(key)
                .putLong(2)
                .array();
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int index = 3 | (4 << 5) | (5 << 10);
        buffer.putShort(16 + index * 2, (short) 1);
        buffer.putLong(16 + 32 * 32 * 32 * 2 + 8, 7L << 27);

        List<long[]> voxels = new ArrayList<>();
        VoxySectionParser.parseAll(Zstd.compress(raw),
                (x, y, z, blockId) -> voxels.add(new long[]{x, y, z, blockId}));

        assertEquals(1, voxels.size());
        assertEquals(128 + 3 * 4, voxels.get(0)[0]);
        assertEquals(-128 + 4 * 4, voxels.get(0)[1]);
        assertEquals(-256 + 5 * 4, voxels.get(0)[2]);
        assertEquals(7, voxels.get(0)[3]);
    }
}
