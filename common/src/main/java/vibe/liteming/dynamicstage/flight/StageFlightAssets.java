package vibe.liteming.dynamicstage.flight;

import com.google.gson.Gson;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import vibe.liteming.dynamicstage.util.ContentHash;

import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;

/** World-local, content-addressed storage for validated CMDCam stage flights. */
public final class StageFlightAssets {

    private static final String ACTIVE_REF = "active.ref";
    private static final String CMDCAM_SAVED_DATA = "cmdcam_Scenes.dat";
    private static final Gson GSON = new Gson();

    private StageFlightAssets() {
    }

    public static Asset importFromInbox(Path worldRoot, String stageId, String name, int sceneSlot)
            throws IOException {
        validateStageId(stageId);
        if (name == null || !name.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IOException("Flight import name must use 1-64 letters, digits, '_' or '-'");
        }
        Path inbox = inboxDirectory(worldRoot);
        Files.createDirectories(inbox);
        Path source = inbox.resolve(name + ".json").normalize();
        if (!source.getParent().equals(inbox) || Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Flight import file not found: " + source);
        }
        long inputBytes = Files.size(source);
        if (inputBytes <= 0 || inputBytes > StageFlightCodec.MAX_BYTES) {
            throw new IOException("Flight import exceeds the supported size");
        }
        StageFlightCodec.Scene scene = StageFlightCodec.select(readBounded(source), sceneSlot);
        return store(worldRoot, stageId, scene);
    }

    public static Asset importFromCMDCam(Path worldRoot, String stageId, String sceneName) throws IOException {
        validateStageId(stageId);
        byte[] sceneJson = GSON.toJson(CMDCamSavedData.readScene(cmdcamFile(worldRoot), sceneName))
                .getBytes(StandardCharsets.UTF_8);
        return store(worldRoot, stageId, StageFlightCodec.readSingle(sceneJson));
    }

    public static Asset importFromCMDCam(ServerLevel sourceLevel, Path worldRoot,
                                         String stageId, String sceneName) throws IOException {
        validateStageId(stageId);
        com.google.gson.JsonObject live = CMDCamSavedData.readLiveScene(sourceLevel, sceneName);
        if (live == null && !sourceLevel.dimension().equals(Level.OVERWORLD)) {
            live = CMDCamSavedData.readLiveScene(sourceLevel.getServer().overworld(), sceneName);
        }
        if (live == null) {
            return importFromCMDCam(worldRoot, sourceLevel.dimension(), stageId, sceneName);
        }
        byte[] sceneJson = GSON.toJson(live).getBytes(StandardCharsets.UTF_8);
        return store(worldRoot, stageId, StageFlightCodec.readSingle(sceneJson));
    }

    public static Asset importFromCMDCam(Path worldRoot, ResourceKey<Level> dimension,
                                         String stageId, String sceneName) throws IOException {
        validateStageId(stageId);
        JsonObjectHolder scene = readCMDCamScene(worldRoot, dimension, sceneName);
        byte[] sceneJson = GSON.toJson(scene.value())
                .getBytes(StandardCharsets.UTF_8);
        return store(worldRoot, stageId, StageFlightCodec.readSingle(sceneJson));
    }

    public static java.util.List<String> listCMDCamScenes(Path worldRoot) {
        try {
            return CMDCamSavedData.listScenes(cmdcamFile(worldRoot));
        } catch (IOException | RuntimeException e) {
            return java.util.List.of();
        }
    }

    public static java.util.List<String> listCMDCamScenes(ServerLevel sourceLevel, Path worldRoot) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        names.addAll(CMDCamSavedData.listLiveScenes(sourceLevel));
        if (!sourceLevel.dimension().equals(Level.OVERWORLD)) {
            names.addAll(CMDCamSavedData.listLiveScenes(sourceLevel.getServer().overworld()));
        }
        names.addAll(listCMDCamScenes(worldRoot, sourceLevel.dimension()));
        return names.stream().sorted().toList();
    }

    public static java.util.List<String> listCMDCamScenes(Path worldRoot, ResourceKey<Level> dimension) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        try { names.addAll(CMDCamSavedData.listScenes(cmdcamFile(worldRoot, dimension))); }
        catch (IOException | RuntimeException ignored) { }
        if (!isOverworld(dimension)) {
            try { names.addAll(CMDCamSavedData.listScenes(cmdcamFile(worldRoot))); }
            catch (IOException | RuntimeException ignored) { }
        }
        return names.stream().sorted().toList();
    }

    private static JsonObjectHolder readCMDCamScene(Path worldRoot, ResourceKey<Level> dimension,
                                                     String sceneName) throws IOException {
        Path primary = cmdcamFile(worldRoot, dimension);
        try {
            return new JsonObjectHolder(CMDCamSavedData.readScene(primary, sceneName));
        } catch (IOException primaryError) {
            if (isOverworld(dimension)) {
                throw primaryError;
            }
            Path fallback = cmdcamFile(worldRoot);
            try {
                return new JsonObjectHolder(CMDCamSavedData.readScene(fallback, sceneName));
            } catch (IOException fallbackError) {
                throw new IOException(primaryError.getMessage() + "; fallback checked " + fallback, fallbackError);
            }
        }
    }

    public static Path cmdcamFile(Path worldRoot) {
        return worldRoot.resolve("data").resolve(CMDCAM_SAVED_DATA).toAbsolutePath().normalize();
    }

    public static Path cmdcamFile(Path worldRoot, ResourceKey<Level> dimension) {
        if (isOverworld(dimension)) {
            return cmdcamFile(worldRoot);
        }
        ResourceLocation id = dimension.location();
        Path dimensionRoot = worldRoot.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
        return dimensionRoot.resolve("data").resolve(CMDCAM_SAVED_DATA).toAbsolutePath().normalize();
    }

    private static boolean isOverworld(ResourceKey<Level> dimension) {
        ResourceLocation id = dimension.location();
        return "minecraft".equals(id.getNamespace()) && "overworld".equals(id.getPath());
    }

    private static Asset store(Path worldRoot, String stageId, StageFlightCodec.Scene scene) throws IOException {
        byte[] canonical = scene.json();
        String hash = ContentHash.sha256Hex(canonical);
        Path directory = stageDirectory(worldRoot, stageId);
        Files.createDirectories(directory);
        Path target = directory.resolve(hash + ".json");
        if (!Files.isRegularFile(target) || Files.size(target) != canonical.length
                || !hash.equals(ContentHash.sha256Hex(target))) {
            atomicWrite(directory, target, canonical);
        }
        atomicWrite(directory, directory.resolve(ACTIVE_REF),
                (hash + System.lineSeparator()).getBytes(StandardCharsets.US_ASCII));
        return new Asset(target, hash, canonical.length, scene.durationMillis(), scene.pointCount(), canonical);
    }

    public static Asset install(Path worldRoot, String stageId, byte[] sceneJson) throws IOException {
        validateStageId(stageId);
        return store(worldRoot, stageId, StageFlightCodec.readSingle(sceneJson));
    }

    public static boolean clear(Path worldRoot, String stageId) throws IOException {
        validateStageId(stageId);
        return Files.deleteIfExists(stageDirectory(worldRoot, stageId).resolve(ACTIVE_REF));
    }

    @Nullable
    public static Asset findConfigured(Path worldRoot, String stageId) {
        try {
            validateStageId(stageId);
            Path reference = stageDirectory(worldRoot, stageId).resolve(ACTIVE_REF);
            if (!Files.isRegularFile(reference) || Files.size(reference) > 128L) {
                return null;
            }
            String hash = Files.readString(reference, StandardCharsets.US_ASCII).trim();
            return load(worldRoot, stageId, hash);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @Nullable
    public static Asset load(MinecraftServer server, String stageId, String hash) {
        return load(server.getWorldPath(LevelResource.ROOT), stageId, hash);
    }

    @Nullable
    public static Asset load(Path worldRoot, String stageId, String hash) {
        if (!ContentHash.isSha256(hash)) {
            return null;
        }
        try {
            Path directory = stageDirectory(worldRoot, stageId);
            Path path = directory.resolve(hash + ".json").normalize();
            if (!path.getParent().equals(directory) || !Files.isRegularFile(path)) {
                return null;
            }
            long size = Files.size(path);
            if (size <= 0 || size > StageFlightCodec.MAX_BYTES) {
                return null;
            }
            byte[] bytes = readBounded(path);
            if (!hash.equals(ContentHash.sha256Hex(bytes))) {
                return null;
            }
            StageFlightCodec.Scene scene = StageFlightCodec.readSingle(bytes);
            byte[] canonical = scene.json();
            if (!java.util.Arrays.equals(bytes, canonical)) {
                return null;
            }
            return new Asset(path, hash, bytes.length, scene.durationMillis(), scene.pointCount(), bytes);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static Path inboxDirectory(Path worldRoot) {
        return worldRoot.resolve("data").resolve("dynamicstage").resolve("flight_imports")
                .toAbsolutePath().normalize();
    }

    public static Path stageDirectory(Path worldRoot, String stageId) {
        return worldRoot.resolve("data").resolve("dynamicstage").resolve("flights")
                .resolve(ContentHash.sha256Hex(stageId.getBytes(StandardCharsets.UTF_8)).substring(0, 24))
                .toAbsolutePath().normalize();
    }

    private static void validateStageId(String stageId) throws IOException {
        if (stageId == null || stageId.isBlank() || stageId.length() > 128) {
            throw new IOException("Invalid stage id");
        }
    }

    private static void atomicWrite(Path directory, Path target, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(directory, target.getFileName().toString(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] readBounded(Path path) throws IOException {
        try (java.io.InputStream input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(StageFlightCodec.MAX_BYTES + 1);
            if (bytes.length > StageFlightCodec.MAX_BYTES) {
                throw new IOException("Flight asset exceeds the supported size");
            }
            return bytes;
        }
    }

    public record Asset(Path path, String hash, int bytes, long durationMillis, int pointCount, byte[] sceneJson) {
        public Asset {
            sceneJson = sceneJson.clone();
        }

        @Override
        public byte[] sceneJson() {
            return sceneJson.clone();
        }
    }

    private record JsonObjectHolder(com.google.gson.JsonObject value) { }
}
