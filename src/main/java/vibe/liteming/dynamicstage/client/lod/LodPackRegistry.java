package vibe.liteming.dynamicstage.client.lod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Resolves client-distributed LOD packages without accepting paths from the server. */
public final class LodPackRegistry {

    public static final int FORMAT_VERSION = 1;
    public static final String DH_VERSION = "3.2";
    public static final int STAGE_MIN_Y = -64;
    public static final int STAGE_HEIGHT = 384;
    private static final long MAX_MANIFEST_BYTES = 64 * 1024L;
    private static final String DH_DATABASE = "DistantHorizons.sqlite";

    private LodPackRegistry() {
    }

    public static DhPack loadDh(ResourceLocation id) throws IOException {
        return loadDh(rootDirectory(), id);
    }

    static DhPack loadDh(Path rootDirectory, ResourceLocation id) throws IOException {
        Path root = rootDirectory.toAbsolutePath().normalize();
        Path directory = root.resolve(id.getNamespace()).resolve(id.getPath()).toAbsolutePath().normalize();
        if (!directory.startsWith(root) || containsSymbolicLink(root, directory)) {
            throw new IOException("LOD pack path is unsafe");
        }
        Path manifestPath = directory.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)
                || Files.size(manifestPath) <= 0L || Files.size(manifestPath) > MAX_MANIFEST_BYTES) {
            throw new IOException("missing or oversized manifest.json");
        }
        JsonObject manifest;
        try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
            manifest = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("invalid LOD pack manifest", e);
        }
        requireInt(manifest, "formatVersion", FORMAT_VERSION);
        requireString(manifest, "backend", "distanthorizons");
        requireString(manifest, "minecraftVersion", "1.20.1");
        requireString(manifest, "distantHorizonsVersion", DH_VERSION);
        requireInt(manifest, "minY", STAGE_MIN_Y);
        requireInt(manifest, "height", STAGE_HEIGHT);

        Path dhDirectory = directory.resolve("dh").normalize();
        Path database = dhDirectory.resolve(DH_DATABASE).normalize();
        if (!dhDirectory.startsWith(directory) || containsSymbolicLink(directory, database)
                || !Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS) || Files.size(database) < 100L) {
            throw new IOException("missing dh/" + DH_DATABASE);
        }
        validateSqliteHeader(database);
        return new DhPack(id, directory, dhDirectory, database);
    }

    public static Path rootDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("dynamicstage").resolve("lodpacks")
                .toAbsolutePath().normalize();
    }

    private static void requireInt(JsonObject object, String key, int expected) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber()
                || object.get(key).getAsInt() != expected) {
            throw new IOException(key + " must be " + expected);
        }
    }

    private static void requireString(JsonObject object, String key, String expected) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isString()
                || !expected.equals(object.get(key).getAsString())) {
            throw new IOException(key + " must be '" + expected + "'");
        }
    }

    private static void validateSqliteHeader(Path database) throws IOException {
        byte[] expected = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
        byte[] actual = new byte[expected.length];
        try (java.io.InputStream input = Files.newInputStream(database)) {
            if (input.readNBytes(actual, 0, actual.length) != actual.length
                    || !java.util.Arrays.equals(expected, actual)) {
                throw new IOException("DH database is not SQLite format 3");
            }
        }
    }

    private static boolean containsSymbolicLink(Path root, Path leaf) {
        Path current = root;
        if (Files.isSymbolicLink(current)) {
            return true;
        }
        Path relative = root.relativize(leaf);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    public record DhPack(ResourceLocation id, Path directory, Path dhDirectory, Path database) {
    }
}
