package vibe.liteming.dynamicstage.flight;

import com.google.gson.Gson;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import vibe.liteming.dynamicstage.util.ContentHash;
import vibe.liteming.dynamicstage.platform.StagePlatform;

import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.LinkOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;

/** World-local, content-addressed storage for validated CMDCam stage flights. */
public final class StageFlightAssets {

    private static final String ACTIVE_REF = "active.ref";
    private static final String CMDCAM_SAVED_DATA = "cmdcam_Scenes.dat";
    private static final int LIBRARY_FORMAT = 1;
    private static final long MAX_LIBRARY_BYTES = StageFlightCodec.MAX_BYTES + 4096L;
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
        return storeImported(worldRoot, stageId, scene);
    }

    public static Asset importFromCMDCam(Path worldRoot, String stageId, String sceneName) throws IOException {
        validateStageId(stageId);
        JsonObjectHolder scene = readCMDCamScene(worldRoot, sceneName);
        byte[] sceneJson = GSON.toJson(scene.value()).getBytes(StandardCharsets.UTF_8);
        return storeImported(worldRoot, stageId, StageFlightCodec.readSingle(sceneJson));
    }

    public static Asset importFromCMDCamFile(Path file, Path worldRoot,
                                             String stageId, String sceneName) throws IOException {
        validateStageId(stageId);
        Path source = normalizeExternalFile(file);
        String selected = resolveSceneName(source, sceneName);
        byte[] sceneJson = GSON.toJson(CMDCamSavedData.readScene(source, selected))
                .getBytes(StandardCharsets.UTF_8);
        return storeImported(worldRoot, stageId, StageFlightCodec.readSingle(sceneJson));
    }

    public static List<String> listCMDCamScenesFile(Path file) {
        try {
            return CMDCamSavedData.listScenes(normalizeExternalFile(file));
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
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
        return storeImported(worldRoot, stageId, StageFlightCodec.readSingle(sceneJson));
    }

    public static Asset importFromCMDCam(Path worldRoot, ResourceKey<Level> dimension,
                                         String stageId, String sceneName) throws IOException {
        validateStageId(stageId);
        JsonObjectHolder scene = readCMDCamScene(worldRoot, dimension, sceneName);
        byte[] sceneJson = GSON.toJson(scene.value())
                .getBytes(StandardCharsets.UTF_8);
        return storeImported(worldRoot, stageId, StageFlightCodec.readSingle(sceneJson));
    }

    public static Asset importFromLibrary(Path worldRoot, String stageId, String flightName) throws IOException {
        validateStageId(stageId);
        return store(worldRoot, stageId, readLibrary(flightName));
    }

    public static List<String> listLibraryFlights() {
        try {
            Path root = libraryDirectory();
            if (!Files.isDirectory(root)) {
                return List.of();
            }
            try (var files = Files.list(root)) {
                return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .map(path -> path.getFileName().toString())
                        .filter(name -> name.endsWith(".dat"))
                        .map(name -> name.substring(0, name.length() - 4))
                        .filter(StageFlightAssets::validLibraryName)
                        .sorted().toList();
            }
        } catch (IOException | RuntimeException | AssertionError | LinkageError e) {
            return List.of();
        }
    }

    public static Path libraryDirectory() {
        return StagePlatform.gameDirectory().resolve("dynamicstage").toAbsolutePath().normalize();
    }

    public static java.util.List<String> listCMDCamScenes(Path worldRoot) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Path file : overworldCMDCamFiles(worldRoot)) {
            try { names.addAll(CMDCamSavedData.listScenes(file)); }
            catch (IOException | RuntimeException ignored) { }
        }
        return names.stream().sorted().toList();
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
        LinkedHashSet<String> names = new LinkedHashSet<>();
        for (Path file : cmdcamFiles(worldRoot, dimension)) {
            try { names.addAll(CMDCamSavedData.listScenes(file)); }
            catch (IOException | RuntimeException ignored) { }
        }
        return names.stream().sorted().toList();
    }

    private static JsonObjectHolder readCMDCamScene(Path worldRoot, ResourceKey<Level> dimension,
                                                     String sceneName) throws IOException {
        IOException last = null;
        for (Path file : cmdcamFiles(worldRoot, dimension)) {
            try {
                return new JsonObjectHolder(CMDCamSavedData.readScene(file, sceneName));
            } catch (IOException error) {
                last = error;
            }
        }
        throw last == null ? new IOException("CMDCam has not saved any scenes in this world") : last;
    }

    private static JsonObjectHolder readCMDCamScene(Path worldRoot, String sceneName) throws IOException {
        IOException last = null;
        for (Path file : overworldCMDCamFiles(worldRoot)) {
            try {
                return new JsonObjectHolder(CMDCamSavedData.readScene(file, sceneName));
            } catch (IOException error) {
                last = error;
            }
        }
        throw last == null ? new IOException("CMDCam has not saved any scenes in this world") : last;
    }

    public static Path cmdcamFile(Path worldRoot) {
        return worldRoot.resolve("data").resolve(CMDCAM_SAVED_DATA).toAbsolutePath().normalize();
    }

    public static Path cmdcamFile(Path worldRoot, ResourceKey<Level> dimension) {
        if (isOverworld(dimension)) {
            return worldRoot.resolve("dimensions").resolve("minecraft").resolve("overworld")
                    .resolve("data").resolve("cmdcam").resolve("scenes.dat")
                    .toAbsolutePath().normalize();
        }
        ResourceLocation id = dimension.location();
        Path dimensionRoot = worldRoot.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
        return dimensionRoot.resolve("data").resolve("cmdcam").resolve("scenes.dat").toAbsolutePath().normalize();
    }

    private static List<Path> cmdcamFiles(Path worldRoot, ResourceKey<Level> dimension) {
        if (isOverworld(dimension)) {
            return overworldCMDCamFiles(worldRoot);
        }
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        ResourceLocation id = dimension.location();
        Path dimensionRoot = worldRoot.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
        files.add(dimensionRoot.resolve("data").resolve("cmdcam").resolve("scenes.dat").toAbsolutePath().normalize());
        files.add(dimensionRoot.resolve("data").resolve(CMDCAM_SAVED_DATA).toAbsolutePath().normalize());
        files.add(worldRoot.resolve("data").resolve("cmdcam").resolve("scenes.dat").toAbsolutePath().normalize());
        files.add(cmdcamFile(worldRoot));
        return List.copyOf(files);
    }

    private static List<Path> overworldCMDCamFiles(Path worldRoot) {
        LinkedHashSet<Path> files = new LinkedHashSet<>();
        Path modern = worldRoot.resolve("dimensions").resolve("minecraft").resolve("overworld");
        files.add(modern.resolve("data").resolve("cmdcam").resolve("scenes.dat").toAbsolutePath().normalize());
        files.add(modern.resolve("data").resolve(CMDCAM_SAVED_DATA).toAbsolutePath().normalize());
        files.add(worldRoot.resolve("data").resolve("cmdcam").resolve("scenes.dat").toAbsolutePath().normalize());
        files.add(cmdcamFile(worldRoot));
        return List.copyOf(files);
    }

    private static Path normalizeExternalFile(Path file) throws IOException {
        if (file == null) {
            throw new IOException("CMDCam source file is missing");
        }
        Path source = file.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("CMDCam source file not found: " + source);
        }
        long size = Files.size(source);
        if (size <= 0L || size > 4L * 1024L * 1024L) {
            throw new IOException("CMDCam scene database exceeds the supported size");
        }
        return source;
    }

    private static String resolveSceneName(Path source, String requested) throws IOException {
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        List<String> scenes = CMDCamSavedData.listScenes(source);
        if (scenes.size() == 1) {
            return scenes.get(0);
        }
        if (scenes.isEmpty()) {
            throw new IOException("CMDCam has not saved any scenes in " + source);
        }
        throw new IOException("CMDCam file contains multiple scenes; specify one of: "
                + String.join(", ", scenes));
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

    private static Asset storeImported(Path worldRoot, String stageId, StageFlightCodec.Scene scene)
            throws IOException {
        if (validLibraryName(stageId)) {
            try {
                writeLibrary(stageId, scene);
            } catch (AssertionError | LinkageError ignored) {
                // Common-only tests and tooling may not provide a platform game directory.
            }
        }
        return store(worldRoot, stageId, scene);
    }

    private static void writeLibrary(String name, StageFlightCodec.Scene scene) throws IOException {
        Path root = libraryDirectory();
        Files.createDirectories(root);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Format", LIBRARY_FORMAT);
        tag.putString("Name", name);
        tag.putByteArray("Flight", scene.json());
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(tag, output);
        atomicWrite(root, libraryFile(name), output.toByteArray());
    }

    private static StageFlightCodec.Scene readLibrary(String name) throws IOException {
        Path file = libraryFile(name);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unknown global flight '" + name + "' (checked " + file + ")");
        }
        long size = Files.size(file);
        if (size <= 0L || size > MAX_LIBRARY_BYTES) {
            throw new IOException("Global flight file exceeds the supported size");
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(file))))) {
            CompoundTag tag = NbtIo.read(input, new NbtAccounter(MAX_LIBRARY_BYTES * 4L));
            if (tag == null || tag.getInt("Format") != LIBRARY_FORMAT || !name.equals(tag.getString("Name"))) {
                throw new IOException("Invalid global flight file: " + name);
            }
            return StageFlightCodec.readSingle(tag.getByteArray("Flight"));
        } catch (RuntimeException e) {
            throw new IOException("Invalid global flight file: " + name, e);
        }
    }

    private static Path libraryFile(String name) throws IOException {
        if (!validLibraryName(name)) {
            throw new IOException("Flight name must use 1-64 letters, digits, '_' or '-'");
        }
        Path root = libraryDirectory();
        Path file = root.resolve(name + ".dat").normalize();
        if (!file.getParent().equals(root)) {
            throw new IOException("Invalid flight library path");
        }
        return file;
    }

    private static boolean validLibraryName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,64}");
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
