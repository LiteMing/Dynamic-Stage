package vibe.liteming.dynamicstage.client.lod;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class DhPackOptimizerTest {

    @Test
    void rowRadiusUsesDhDetailLevelWidth() throws Exception {
        assertTrue(DhPackOptimizer.rowIntersectsRadius(0, 1, 2, 96, 160, 1));
        assertFalse(DhPackOptimizer.rowIntersectsRadius(0, 1, 2, 0, 0, 32));
        assertTrue(DhPackOptimizer.rowIntersectsRadius(1, 1, 1, 200, 200, 16));
    }

    @Test
    void columnRadiusKeepsSquaresTouchingTheCircle() throws Exception {
        assertTrue(DhPackOptimizer.columnIntersectsRadius(0, 0, 0, 10, 10, 10, 10, 1));
        assertTrue(DhPackOptimizer.columnIntersectsRadius(2, 0, 0, 2, 2, 12, 12, 2));
        assertFalse(DhPackOptimizer.columnIntersectsRadius(2, 0, 0, 2, 2, 4, 4, 1));
        assertTrue(DhPackOptimizer.columnIntersectsRadius(20, 0, 0, 63, 63, 0, 0, -1));
    }

    @Test
    void realDatabaseOptimizationIsReadOnlyAndDeterministic(@TempDir Path temporary) throws Exception {
        String configured = System.getenv("DYNAMICSTAGE_DH_TEST_DATABASE");
        assumeTrue(configured != null && !configured.isBlank(),
                "Set DYNAMICSTAGE_DH_TEST_DATABASE to run the stock-DH integration test");
        Path database = Path.of(configured).toAbsolutePath().normalize();
        assumeTrue(Files.isRegularFile(database), "Configured DH database does not exist");

        Path root = temporary.resolve("lodpacks");
        ResourceLocation sourceId = new ResourceLocation("test", "source");
        Path sourceDirectory = root.resolve("test/source");
        Path sourceDatabase = sourceDirectory.resolve("dh/DistantHorizons.sqlite");
        Files.createDirectories(sourceDatabase.getParent());
        Files.copy(database, sourceDatabase);
        writeManifest(sourceDirectory);
        byte[] sourceBefore = Files.readAllBytes(sourceDatabase);

        LodPackRegistry.DhPack source = LodPackRegistry.loadDh(root, sourceId);
        DhPackOptimizer.Result first = DhPackOptimizer.crop(root, source,
                new ResourceLocation("test", "first"), 40, 319, 0, 0, -1);
        DhPackOptimizer.Result second = DhPackOptimizer.crop(root, source,
                new ResourceLocation("test", "second"), 40, 319, 0, 0, -1);

        assertArrayEquals(sourceBefore, Files.readAllBytes(sourceDatabase));
        assertEquals(first.outputSha256(), second.outputSha256());
        assertEquals(first.sourceRows(), first.outputRows() + first.removedRows());
        assertTrue(first.outputRows() > 0);
        assertTrue(first.removedSegments() > 0);
        assertTrue(first.outputBytes() > 100);
        assertEquals(first.sourceSha256(), second.sourceSha256());
        assertEquals(first.outputSha256(), sha256(first.pack().database()));
        assertEquals(first.pack(), LodPackRegistry.loadDh(root, new ResourceLocation("test", "first")));

        assertThrows(java.io.IOException.class, () -> DhPackOptimizer.crop(root, source,
                new ResourceLocation("test", "empty"), -64, 319, 29_000_000, 29_000_000, 1));
        assertFalse(Files.exists(root.resolve("test/empty")));
    }

    private static void writeManifest(Path directory) throws Exception {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("formatVersion", LodPackRegistry.FORMAT_VERSION);
        manifest.addProperty("backend", "distanthorizons");
        manifest.addProperty("minecraftVersion", "1.20.1");
        manifest.addProperty("distantHorizonsVersion", LodPackRegistry.DH_VERSION);
        manifest.addProperty("minY", LodPackRegistry.STAGE_MIN_Y);
        manifest.addProperty("height", LodPackRegistry.STAGE_HEIGHT);
        Files.writeString(directory.resolve("manifest.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(manifest) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(path));
        return HexFormat.of().formatHex(digest.digest());
    }
}
