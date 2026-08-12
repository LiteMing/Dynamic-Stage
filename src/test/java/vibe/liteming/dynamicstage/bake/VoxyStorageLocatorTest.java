package vibe.liteming.dynamicstage.bake;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class VoxyStorageLocatorTest {

    @Test
    void worldIdMatchesVoxyDimensionHashAlgorithm() {
        assertEquals("726dfed4c98d73d9d706d74da3636835",
                VoxyStorageLocator.worldId(123456789L, "ResourceKey[minecraft:dimension / minecraft:overworld]"));
        assertNotEquals(
                VoxyStorageLocator.worldId(123456789L, "ResourceKey[minecraft:dimension / minecraft:overworld]"),
                VoxyStorageLocator.worldId(123456789L, "ResourceKey[minecraft:dimension / minecraft:the_nether]"));
    }
}
