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
}
