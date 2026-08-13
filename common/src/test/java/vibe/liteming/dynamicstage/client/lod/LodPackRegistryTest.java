package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LodPackRegistryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsAValidatedExternalDhDatabase() throws IOException {
        ResourceLocation id = new ResourceLocation("stages", "forest/night");
        Path directory = createPack(id);

        LodPackRegistry.DhPack pack = LodPackRegistry.loadDh(temporaryDirectory, id);

        assertEquals(directory.toAbsolutePath().normalize(), pack.directory());
        assertEquals(directory.resolve("dh/DistantHorizons.sqlite"), pack.database());
    }

    @Test
    void rejectsWrongManifestAndDatabaseHeader() throws IOException {
        ResourceLocation id = new ResourceLocation("stages", "broken");
        Path directory = createPack(id);
        Files.writeString(directory.resolve("manifest.json"), manifest().replace("\"height\": 384", "\"height\": 256"));
        assertThrows(IOException.class, () -> LodPackRegistry.loadDh(temporaryDirectory, id));

        Files.writeString(directory.resolve("manifest.json"), manifest());
        Files.write(directory.resolve("dh/DistantHorizons.sqlite"), new byte[100]);
        assertThrows(IOException.class, () -> LodPackRegistry.loadDh(temporaryDirectory, id));
    }

    @Test
    void rejectsSymbolicLinkPathsWhenSupported() throws IOException {
        ResourceLocation id = new ResourceLocation("stages", "linked");
        Path outside = temporaryDirectory.resolve("linked-target");
        Files.createDirectories(outside);
        Path namespace = temporaryDirectory.resolve(id.getNamespace());
        try {
            Files.createSymbolicLink(namespace, outside);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        assertThrows(IOException.class, () -> LodPackRegistry.loadDh(temporaryDirectory, id));
    }

    @Test
    void acceptsAnExternalVoxyWorldDirectory() throws IOException {
        ResourceLocation id = new ResourceLocation("stages", "voxy_city");
        String worldId = "0123456789abcdef0123456789abcdef";
        Path directory = temporaryDirectory.resolve(id.getNamespace()).resolve(id.getPath());
        Path storage = Files.createDirectories(directory.resolve("voxy").resolve(worldId).resolve("storage"));
        Files.writeString(directory.resolve("manifest.json"), voxyManifest(worldId));
        Files.writeString(storage.resolve("CURRENT"), "MANIFEST-000001\n");
        Files.write(storage.resolve("MANIFEST-000001"), new byte[]{1});

        LodPackRegistry.Pack loaded = LodPackRegistry.load(temporaryDirectory, id);

        assertTrue(loaded instanceof LodPackRegistry.VoxyPack);
        LodPackRegistry.VoxyPack pack = (LodPackRegistry.VoxyPack) loaded;
        assertEquals(worldId, pack.worldId());
        assertEquals(storage.toAbsolutePath().normalize(), pack.storageDirectory());
    }

    @Test
    void rejectsInvalidOrMissingVoxyStorage() throws IOException {
        ResourceLocation id = new ResourceLocation("stages", "bad_voxy");
        Path directory = temporaryDirectory.resolve(id.getNamespace()).resolve(id.getPath());
        Files.createDirectories(directory.resolve("voxy"));
        Files.writeString(directory.resolve("manifest.json"), voxyManifest("not-a-world-id"));
        assertThrows(IOException.class, () -> LodPackRegistry.load(temporaryDirectory, id));

        String worldId = "fedcba9876543210fedcba9876543210";
        Files.writeString(directory.resolve("manifest.json"), voxyManifest(worldId));
        Files.createDirectories(directory.resolve("voxy").resolve(worldId).resolve("storage"));
        assertThrows(IOException.class, () -> LodPackRegistry.load(temporaryDirectory, id));
    }

    private Path createPack(ResourceLocation id) throws IOException {
        Path directory = temporaryDirectory.resolve(id.getNamespace()).resolve(id.getPath());
        Path dh = Files.createDirectories(directory.resolve("dh"));
        Files.writeString(directory.resolve("manifest.json"), manifest());
        byte[] database = new byte[100];
        byte[] header = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, database, 0, header.length);
        Files.write(dh.resolve("DistantHorizons.sqlite"), database);
        return directory;
    }

    private static String manifest() {
        return """
                {
                  "formatVersion": 1,
                  "backend": "distanthorizons",
                  "minecraftVersion": "1.20.1",
                  "distantHorizonsVersion": "3.2",
                  "minY": -64,
                  "height": 384
                }
                """;
    }

    private static String voxyManifest(String worldId) {
        return """
                {
                  "formatVersion": 1,
                  "backend": "voxy",
                  "minecraftVersion": "1.20.1",
                  "voxyVersion": "0.2.14",
                  "worldId": "%s",
                  "minY": -64,
                  "height": 384
                }
                """.formatted(worldId);
    }
}
