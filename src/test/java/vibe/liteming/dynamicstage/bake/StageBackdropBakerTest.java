package vibe.liteming.dynamicstage.bake;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vibe.liteming.dynamicstage.backdrop.BackdropBlobIO;
import vibe.liteming.dynamicstage.backdrop.BackdropProducts;
import vibe.liteming.dynamicstage.bake.lod.Voxel;
import vibe.liteming.dynamicstage.stage.StageSession;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class StageBackdropBakerTest {

    @TempDir
    Path worldRoot;

    @Test
    void encodesPortableMergedColumnsAndContentAddress() throws Exception {
        BlockPos anchor = new BlockPos(100, 64, -200);
        List<Voxel> voxels = new ArrayList<>(List.of(
                new Voxel(101, 64, -198, 0xFF112233),
                new Voxel(101, 65, -198, 0xFF112233),
                new Voxel(104, 60, -196, 0xFF445566, 4, 8)));
        String requestKey = "111111111111111111111111";

        BackdropProducts.Product product = StageBackdropBaker.encode(
                worldRoot, "dynamicstage:test", "minecraft:overworld", anchor,
                StageSession.SOURCE_DH, requestKey, voxels);
        BackdropBlobIO.Blob blob = BackdropBlobIO.read(Files.readAllBytes(product.path()));

        assertEquals(2, blob.columns().size());
        assertEquals(1, blob.columns().get(0).x());
        assertEquals(2, blob.columns().get(0).z());
        assertEquals(64, blob.columns().get(0).yStart());
        assertEquals(66, blob.columns().get(0).yEnd());
        assertEquals(2, blob.columns().get(1).lodLevel());
        assertEquals(requestKey, blob.manifest().getWorldHash());
        assertEquals(product.hash(), BackdropProducts.sha256Hex(Files.readAllBytes(product.path())));
    }

    @Test
    void requestKeySeparatesSourceDimensions() throws Exception {
        Path source = worldRoot.resolve("source.sqlite");
        Files.writeString(source, "test");
        BlockPos anchor = new BlockPos(10, 64, 20);

        String overworld = StageBackdropBaker.requestKey(
                source, anchor, StageSession.SOURCE_DH, "minecraft:overworld");
        String nether = StageBackdropBaker.requestKey(
                source, anchor, StageSession.SOURCE_DH, "minecraft:the_nether");

        assertNotEquals(overworld, nether);
        assertEquals(overworld, StageBackdropBaker.requestKey(
                source, anchor, StageSession.SOURCE_DH, "minecraft:overworld"));
    }
}
