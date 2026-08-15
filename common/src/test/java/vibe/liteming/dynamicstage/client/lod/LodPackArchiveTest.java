package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodPackArchiveTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void exportsAndInstallsAStandalonePackage() throws IOException {
        ResourceLocation id = new ResourceLocation("stages", "forest");
        Path packageDirectory = temporaryDirectory.resolve("source/stages/forest");
        Path dh = Files.createDirectories(packageDirectory.resolve("dh"));
        Files.writeString(packageDirectory.resolve("manifest.json"), """
                {
                  "formatVersion": 1,
                  "backend": "distanthorizons",
                  "minecraftVersion": "1.20.1",
                  "distantHorizonsVersion": "3.2",
                  "minY": -64,
                  "height": 384
                }
                """);
        byte[] database = new byte[100];
        byte[] header = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, database, 0, header.length);
        Files.write(dh.resolve("DistantHorizons.sqlite"), database);
        Files.writeString(dh.resolve("LOG"), "diagnostic");

        Path archive = temporaryDirectory.resolve("forest.dstlod");
        LodPackArchive.ArchiveInfo info = LodPackArchive.create(packageDirectory, archive);
        assertEquals(2, info.files());
        assertTrue(info.bytes() > 0L);

        Path installedRoot = temporaryDirectory.resolve("installed");
        LodPackArchive.install(archive, installedRoot, id, 1024 * 1024L);
        assertTrue(Files.isRegularFile(installedRoot.resolve("stages/forest/manifest.json")));
        assertFalse(Files.exists(installedRoot.resolve("stages/forest/dh/LOG")));
        assertEquals(installedRoot.resolve("stages/forest/dh/DistantHorizons.sqlite").toAbsolutePath().normalize(),
                LodPackRegistry.loadDh(installedRoot, id).database());
    }
}
