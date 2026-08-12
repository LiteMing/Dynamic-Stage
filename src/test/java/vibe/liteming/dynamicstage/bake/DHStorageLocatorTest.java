package vibe.liteming.dynamicstage.bake;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DHStorageLocatorTest {

    private static final Path ROOT = Path.of("world");

    @Test
    void mapsVanillaAndCustomDimensionSaveFolders() {
        assertEquals(ROOT, DHStorageLocator.dimensionRoot(ROOT, ResourceLocation.parse("minecraft:overworld")));
        assertEquals(ROOT.resolve("DIM-1"),
                DHStorageLocator.dimensionRoot(ROOT, ResourceLocation.parse("minecraft:the_nether")));
        assertEquals(ROOT.resolve("DIM1"),
                DHStorageLocator.dimensionRoot(ROOT, ResourceLocation.parse("minecraft:the_end")));
        assertEquals(ROOT.resolve("dimensions/example/moon"),
                DHStorageLocator.dimensionRoot(ROOT, ResourceLocation.parse("example:moon")));
    }
}
