package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageArenaSnapshotTest {
    @Test
    void coversEveryChunkTouchedByLargeUnalignedStructure() {
        List<ChunkPos> chunks = StageArenaSnapshot.coveredChunks(
                new BlockPos(-66, 80, -100), new Vec3i(133, 91, 200));

        assertEquals(140, chunks.size());
        assertTrue(chunks.contains(new ChunkPos(-5, -7)));
        assertTrue(chunks.contains(new ChunkPos(4, 6)));
    }

    @Test
    void includesBothChunksAcrossNegativeZeroBoundary() {
        assertEquals(List.of(new ChunkPos(-1, 0), new ChunkPos(0, 0)),
                StageArenaSnapshot.coveredChunks(new BlockPos(-1, 0, 0), new Vec3i(2, 1, 1)));
    }
}
