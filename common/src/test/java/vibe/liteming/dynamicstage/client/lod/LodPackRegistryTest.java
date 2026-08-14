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

    @Test
    void linksAnExternalDhDatabaseWithoutCopyingIt() throws IOException {
        ResourceLocation id = new ResourceLocation("stages", "linked_dh");
        Path source = createExternalDhSource("linked-world");
        Path packageRoot = temporaryDirectory.resolve("packages");

        LodPackImporter.ImportResult result = LodPackImporter.importPack(
                packageRoot, id, source, LodPackImporter.Mode.LINK);

        assertEquals(0, result.fileCount());
        Path packageDirectory = packageRoot.resolve("stages/linked_dh");
        assertFalse(Files.exists(packageDirectory.resolve("dh")));
        assertTrue(Files.readString(packageDirectory.resolve("manifest.json")).contains("sourcePath"));
        LodPackRegistry.DhPack loaded = LodPackRegistry.loadDh(packageRoot, id);
        assertEquals(source, loaded.database());
    }

    @Test
    void relativeLinkSurvivesRelocatingTheGameDirectory() throws IOException {
        ResourceLocation id = new ResourceLocation("stages", "portable_dh");
        Path gameDirectory = temporaryDirectory.resolve("original-instance");
        Path packageRoot = gameDirectory.resolve("dynamicstage/lodpacks");
        Path source = gameDirectory.resolve("dynamicstage/lodsources/forest/DistantHorizons.sqlite");
        createDhDatabase(source);

        LodPackImporter.importPack(packageRoot, id, source, LodPackImporter.Mode.LINK_RELATIVE);

        Path manifest = packageRoot.resolve("stages/portable_dh/manifest.json");
        String json = Files.readString(manifest);
        assertTrue(json.contains("../../../lodsources/forest/DistantHorizons.sqlite"));
        assertFalse(json.contains(source.toString()));

        Path relocatedGameDirectory = temporaryDirectory.resolve("relocated-instance");
        Files.move(gameDirectory, relocatedGameDirectory);
        Path relocatedPackageRoot = relocatedGameDirectory.resolve("dynamicstage/lodpacks");
        Path relocatedSource = relocatedGameDirectory
                .resolve("dynamicstage/lodsources/forest/DistantHorizons.sqlite");
        assertEquals(relocatedSource.toAbsolutePath().normalize(),
                LodPackRegistry.loadDh(relocatedPackageRoot, id).database());
    }

    @Test
    void relativeVoxyLinkSurvivesRelocatingTheGameDirectory() throws IOException {
        ResourceLocation id = new ResourceLocation("stages", "portable_voxy");
        String worldId = "0123456789abcdef0123456789abcdef";
        Path gameDirectory = temporaryDirectory.resolve("original-voxy-instance");
        Path packageRoot = gameDirectory.resolve("dynamicstage/lodpacks");
        Path source = createVoxySource(gameDirectory
                .resolve("dynamicstage/lodsources/city").resolve(worldId).resolve("storage"));

        LodPackImporter.importPack(packageRoot, id, source, LodPackImporter.Mode.LINK_RELATIVE);

        Path relocatedGameDirectory = temporaryDirectory.resolve("relocated-voxy-instance");
        Files.move(gameDirectory, relocatedGameDirectory);
        Path relocatedPackageRoot = relocatedGameDirectory.resolve("dynamicstage/lodpacks");
        Path relocatedSource = relocatedGameDirectory.resolve("dynamicstage/lodsources/city")
                .resolve(worldId).resolve("storage");
        LodPackRegistry.VoxyPack loaded = (LodPackRegistry.VoxyPack) LodPackRegistry.load(
                relocatedPackageRoot, id);
        assertEquals(relocatedSource.toAbsolutePath().normalize(), loaded.storageDirectory());
    }

    @Test
    void copiesAnExternalDhDatabaseWhenRequested() throws IOException {
        ResourceLocation id = new ResourceLocation("stages", "copied_dh");
        Path source = createExternalDhSource("copied-world");
        Files.write(source.resolveSibling("DistantHorizons.sqlite-wal"), new byte[]{1, 2, 3});
        Path packageRoot = temporaryDirectory.resolve("packages");

        LodPackImporter.ImportResult result = LodPackImporter.importPack(
                packageRoot, id, source, LodPackImporter.Mode.COPY);

        assertEquals(2, result.fileCount());
        assertTrue(Files.exists(packageRoot.resolve("stages/copied_dh/dh/DistantHorizons.sqlite")));
        assertTrue(Files.exists(packageRoot.resolve("stages/copied_dh/dh/DistantHorizons.sqlite-wal")));
        assertFalse(Files.readString(packageRoot.resolve("stages/copied_dh/manifest.json")).contains("sourcePath"));
        assertEquals(packageRoot.resolve("stages/copied_dh/dh/DistantHorizons.sqlite").toAbsolutePath().normalize(),
                LodPackRegistry.loadDh(packageRoot, id).database());
    }

    @Test
    void linksAnExternalVoxyStorageDirectory() throws IOException {
        ResourceLocation id = new ResourceLocation("stages", "linked_voxy");
        String worldId = "0123456789abcdef0123456789abcdef";
        Path storage = createExternalVoxySource(worldId);
        Path packageRoot = temporaryDirectory.resolve("packages");

        LodPackImporter.importPack(packageRoot, id, storage, LodPackImporter.Mode.LINK);

        LodPackRegistry.VoxyPack loaded = (LodPackRegistry.VoxyPack) LodPackRegistry.load(packageRoot, id);
        assertEquals(storage, loaded.storageDirectory());
        assertTrue(Files.readString(packageRoot.resolve("stages/linked_voxy/manifest.json"))
                .contains("sourcePath"));
    }

    @Test
    void rejectsAmbiguousSourceDirectory() throws IOException {
        Path source = temporaryDirectory.resolve("other-instance/saves");
        Files.createDirectories(source);
        createDhDatabase(source.resolve("one/DistantHorizons.sqlite"));
        createDhDatabase(source.resolve("two/DistantHorizons.sqlite"));
        assertEquals(2, LodPackImporter.discover(source).size());
        assertThrows(IOException.class, () -> LodPackImporter.importPack(
                temporaryDirectory.resolve("packages"), new ResourceLocation("stages", "ambiguous"), source,
                LodPackImporter.Mode.LINK));
    }

    private Path createExternalDhSource(String world) throws IOException {
        Path database = temporaryDirectory.resolve("other-game/saves").resolve(world)
                .resolve("data/DistantHorizons.sqlite");
        createDhDatabase(database);
        return database;
    }

    private Path createExternalVoxySource(String worldId) throws IOException {
        Path storage = temporaryDirectory.resolve("other-game/saves/voxy-world/voxy")
                .resolve(worldId).resolve("storage");
        return createVoxySource(storage);
    }

    private static Path createVoxySource(Path storage) throws IOException {
        Files.createDirectories(storage);
        Files.writeString(storage.resolve("CURRENT"), "MANIFEST-000001\n");
        Files.write(storage.resolve("MANIFEST-000001"), new byte[]{1});
        return storage;
    }

    private static void createDhDatabase(Path database) throws IOException {
        Files.createDirectories(database.getParent());
        byte[] bytes = new byte[100];
        byte[] header = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(header, 0, bytes, 0, header.length);
        Files.write(database, bytes);
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
