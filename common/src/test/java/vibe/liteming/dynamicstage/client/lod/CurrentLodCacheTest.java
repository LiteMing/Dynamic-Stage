package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CurrentLodCacheTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void selectsOnlyOneActiveNativeCache() throws IOException {
        CurrentLodCache dh = cache(LodPackImporter.Backend.DISTANT_HORIZONS, "world-a");
        CurrentLodCache voxy = cache(LodPackImporter.Backend.VOXY, "world-b");

        assertNull(CurrentLodCache.select(List.of()));
        assertEquals(dh, CurrentLodCache.select(List.of(dh)));
        assertThrows(IOException.class, () -> CurrentLodCache.select(List.of(dh, voxy)));
    }

    @Test
    void packageIdUsesBackendAndWorldIdentityInsteadOfAbsolutePath() {
        Path firstGame = temporaryDirectory.resolve("first-instance");
        CurrentLodCache first = new CurrentLodCache(LodPackImporter.Backend.DISTANT_HORIZONS,
                firstGame.resolve("saves/world/data/DistantHorizons.sqlite"), "shared-world");
        Path movedGame = temporaryDirectory.resolve("moved-instance");
        CurrentLodCache moved = new CurrentLodCache(LodPackImporter.Backend.DISTANT_HORIZONS,
                movedGame.resolve("saves/world/data/DistantHorizons.sqlite"), "shared-world");
        CurrentLodCache voxy = cache(LodPackImporter.Backend.VOXY, "shared-world");

        assertEquals(first.automaticPackId(firstGame), moved.automaticPackId(movedGame));
        org.junit.jupiter.api.Assertions.assertNotEquals(first.automaticPackId(firstGame),
                voxy.automaticPackId(temporaryDirectory));
        assertEquals(CurrentLodCache.missingPackId("1", new ResourceLocation("minecraft", "overworld")),
                CurrentLodCache.missingPackId("1", new ResourceLocation("minecraft", "overworld")));
    }

    @Test
    void retainsNativeCacheAvailabilityDiagnostic() throws IOException {
        CurrentLodCache empty = new CurrentLodCache(LodPackImporter.Backend.VOXY,
                temporaryDirectory.resolve("empty"), "world", "Voxy cache is empty");

        assertEquals("Voxy cache is empty", CurrentLodCache.select(List.of(empty)).unavailableReason());
    }

    private CurrentLodCache cache(LodPackImporter.Backend backend, String identity) {
        return new CurrentLodCache(backend, temporaryDirectory.resolve(backend.name()), identity);
    }
}
