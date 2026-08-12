package vibe.liteming.dynamicstage.bake.voxy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoxySectionKeyTest {

    @Test
    void roundTripsSignedCoordinatesAtEveryImportedLevel() {
        for (int level = 0; level <= 2; level++) {
            long key = VoxySectionKey.encode(level, -12345, -17, 54321);
            assertEquals(level, VoxySectionKey.levelOf(key));
            assertEquals(-12345, VoxySectionKey.xOf(key));
            assertEquals(-17, VoxySectionKey.yOf(key));
            assertEquals(54321, VoxySectionKey.zOf(key));
            assertEquals(32 << level, VoxySectionKey.sectionSize(level));
        }
    }
}
