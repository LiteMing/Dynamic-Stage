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
import java.util.regex.Pattern;

/** Resolves client-distributed LOD packages without accepting paths from the server. */
public final class LodPackRegistry {
    public static final int FORMAT_VERSION = 1;
    public static final String DH_VERSION = "3.2";
    public static final String VOXY_VERSION = "0.2.14";
    public static final int STAGE_MIN_Y = -64;
    public static final int STAGE_HEIGHT = 384;
    private static final long MAX_MANIFEST_BYTES = 64 * 1024L;
    private static final String DH_DATABASE = "DistantHorizons.sqlite";
    private static final Pattern VOXY_WORLD_ID = Pattern.compile("[0-9a-f]{32}");

    private LodPackRegistry() {
    }

    public static Pack load(ResourceLocation id) throws IOException {
        return load(rootDirectory(), id);
    }

    public static Pack load(Path rootDirectory, ResourceLocation id) throws IOException {
        Path root = rootDirectory.toAbsolutePath().normalize();
        Path directory = root.resolve(id.getNamespace()).resolve(id.getPath()).toAbsolutePath().normalize();
        if (!directory.startsWith(root) || containsSymbolicLink(root, directory)) {
            throw new IOException("LOD pack path is unsafe");
        }
        JsonObject manifest = readManifest(directory);
        requireInt(manifest, "formatVersion", FORMAT_VERSION);
        requireString(manifest, "minecraftVersion", "1.20.1");
        requireInt(manifest, "minY", STAGE_MIN_Y);
        requireInt(manifest, "height", STAGE_HEIGHT);
        String backend = requireString(manifest, "backend");
        return switch (backend) {
            case "distanthorizons" -> loadDh(id, directory, manifest);
            case "voxy" -> loadVoxy(id, directory, manifest);
            default -> throw new IOException("unsupported LOD backend '" + backend + "'");
        };
    }

    public static DhPack loadDh(ResourceLocation id) throws IOException {
        Pack pack = load(id);
        if (pack instanceof DhPack dh) {
            return dh;
        }
        throw new IOException("LOD pack backend is not distanthorizons");
    }

    static DhPack loadDh(Path rootDirectory, ResourceLocation id) throws IOException {
        Pack pack = load(rootDirectory, id);
        if (pack instanceof DhPack dh) {
            return dh;
        }
        throw new IOException("LOD pack backend is not distanthorizons");
    }

    private static DhPack loadDh(ResourceLocation id, Path directory, JsonObject manifest) throws IOException {
        requireString(manifest, "distantHorizonsVersion", DH_VERSION);
        Path external = externalSource(manifest, directory);
        Path database = external == null
                ? directory.resolve("dh").resolve(DH_DATABASE).normalize()
                : external;
        Path dhDirectory = database.getParent();
        Path safetyRoot = external == null ? directory : database.getRoot();
        if (dhDirectory == null || safetyRoot == null
                || (external == null && !dhDirectory.startsWith(directory))
                || containsSymbolicLink(safetyRoot, database)) {
            throw new IOException("DH database path is unsafe");
        }
        if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnavailableException("missing DH database: " + database);
        }
        if (Files.size(database) < 100L) {
            throw new IOException("DH database is too small: " + database);
        }
        validateSqliteHeader(database);
        return new DhPack(id, directory, dhDirectory, database);
    }

    private static VoxyPack loadVoxy(ResourceLocation id, Path directory, JsonObject manifest) throws IOException {
        requireString(manifest, "voxyVersion", VOXY_VERSION);
        String worldId = requireString(manifest, "worldId");
        if (!VOXY_WORLD_ID.matcher(worldId).matches()) {
            throw new IOException("worldId must be 32 lowercase hexadecimal characters");
        }
        Path external = externalSource(manifest, directory);
        Path storageDirectory = external == null
                ? directory.resolve("voxy").resolve(worldId).resolve("storage").normalize()
                : external;
        Path worldDirectory = storageDirectory.getParent();
        Path baseDirectory = worldDirectory == null ? null : worldDirectory.getParent();
        Path safetyRoot = external == null ? directory : storageDirectory.getRoot();
        if (baseDirectory == null || safetyRoot == null
                || !"storage".equals(storageDirectory.getFileName().toString())
                || worldDirectory.getFileName() == null
                || !worldId.equals(worldDirectory.getFileName().toString())
                || (external == null && !storageDirectory.startsWith(baseDirectory))
                || containsSymbolicLink(safetyRoot, storageDirectory)) {
            throw new IOException("Voxy storage path is unsafe");
        }
        if (!Files.isDirectory(storageDirectory, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(storageDirectory.resolve("CURRENT"), LinkOption.NOFOLLOW_LINKS)
                || !hasRocksManifest(storageDirectory)) {
            throw new UnavailableException("missing Voxy RocksDB storage: " + storageDirectory);
        }
        if (!Files.isWritable(baseDirectory) || !Files.isWritable(storageDirectory)) {
            throw new IOException("Voxy package must be a writable runtime copy");
        }
        return new VoxyPack(id, directory, baseDirectory, storageDirectory, worldId);
    }

    private static Path externalSource(JsonObject manifest, Path directory) throws IOException {
        if (!manifest.has("sourcePath")) {
            return null;
        }
        String raw = requireString(manifest, "sourcePath");
        try {
            Path source = Path.of(raw);
            if (!source.isAbsolute()) {
                source = directory.resolve(source);
            }
            return source.toAbsolutePath().normalize();
        } catch (RuntimeException e) {
            throw new IOException("invalid sourcePath", e);
        }
    }

    private static JsonObject readManifest(Path directory) throws IOException {
        Path manifestPath = directory.resolve("manifest.json");
        if (!Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new UnavailableException("missing LOD package manifest: " + manifestPath);
        }
        if (Files.size(manifestPath) <= 0L || Files.size(manifestPath) > MAX_MANIFEST_BYTES) {
            throw new IOException("empty or oversized manifest.json");
        }
        try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("invalid LOD pack manifest", e);
        }
    }

    public static Path rootDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("dynamicstage").resolve("lodpacks")
                .toAbsolutePath().normalize();
    }

    private static boolean hasRocksManifest(Path storageDirectory) throws IOException {
        try (var stream = Files.list(storageDirectory)) {
            return stream.anyMatch(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && path.getFileName().toString().startsWith("MANIFEST-"));
        }
    }

    private static void requireInt(JsonObject object, String key, int expected) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isNumber() || object.get(key).getAsInt() != expected) {
            throw new IOException(key + " must be " + expected);
        }
    }

    private static void requireString(JsonObject object, String key, String expected) throws IOException {
        if (!expected.equals(requireString(object, key))) {
            throw new IOException(key + " must be '" + expected + "'");
        }
    }

    private static String requireString(JsonObject object, String key) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive()
                || !object.getAsJsonPrimitive(key).isString()) {
            throw new IOException("missing string '" + key + "'");
        }
        return object.get(key).getAsString();
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
        for (Path part : root.relativize(leaf)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                return true;
            }
        }
        return false;
    }

    public sealed interface Pack permits DhPack, VoxyPack {
        ResourceLocation id();
        Path directory();
    }

    public record DhPack(ResourceLocation id, Path directory, Path dhDirectory, Path database) implements Pack {
    }

    public record VoxyPack(ResourceLocation id, Path directory, Path baseDirectory,
                           Path storageDirectory, String worldId) implements Pack {
    }

    public static final class UnavailableException extends IOException {
        public UnavailableException(String message) {
            super(message);
        }
    }
}
