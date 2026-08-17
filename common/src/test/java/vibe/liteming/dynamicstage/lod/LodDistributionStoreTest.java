package vibe.liteming.dynamicstage.lod;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodDistributionStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void automaticallyArchivesAnUnpackedServerPackage() throws Exception {
        Path packages = temporaryDirectory.resolve("dynamicstage/lodpacks");
        Path packageDirectory = packages.resolve("minecraft/gr");
        Files.createDirectories(packageDirectory.resolve("voxy/world/storage"));
        Files.writeString(packageDirectory.resolve("manifest.json"), "{\"formatVersion\":1}");
        Files.writeString(packageDirectory.resolve("voxy/world/storage/CURRENT"), "MANIFEST-000001\n");
        ResourceLocation id = new ResourceLocation("minecraft", "gr");

        LodDistributionStore.HostedArchive archive = LodDistributionStore.hostedArchive(
                List.of(new LodDistributionStore.ResourceRoot(packages,
                        temporaryDirectory.resolve("dynamicstage/.cache/lodarchives"))), id);

        assertNotNull(archive);
        assertTrue(archive.generated());
        assertTrue(archive.offer().serverHosted());
        assertTrue(Files.isRegularFile(archive.path()));
        assertEquals(archive.offer().bytes(), Files.size(archive.path()));
    }
}
