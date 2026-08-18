package vibe.liteming.dynamicstage.template;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import vibe.liteming.dynamicstage.flight.StageFlightAssets;
import vibe.liteming.dynamicstage.platform.StagePlatform;
import vibe.liteming.dynamicstage.stage.StageArenaSnapshot;
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.util.ContentHash;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Atomic JSON manifests and content-addressed vanilla NBT arena snapshots. */
public final class StageTemplateStore {
    private static final long MAX_JSON_BYTES = 1024L * 1024L;
    private static final long MAX_COMPRESSED_ARENA_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_ARENA_NBT_BYTES = 128L * 1024L * 1024L;
    private static final Pattern TEMPLATE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern ARENA_FILE = Pattern.compile("[0-9a-f]{64}\\.nbt");
    private static final Gson PRETTY_GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private StageTemplateStore() {
    }

    public static Path rootDirectory() {
        return StagePlatform.gameDirectory().resolve("dynamicstage").resolve("templates")
                .toAbsolutePath().normalize();
    }

    public static Path arenaDirectory() {
        return StagePlatform.gameDirectory().resolve("dynamicstage").resolve("arenas")
                .toAbsolutePath().normalize();
    }

    public static StageTemplate capture(MinecraftServer server, StageSession session, String id,
                                        StageTemplate.InstanceMode instanceMode,
                                        StageTemplate.LifecyclePolicy lifecyclePolicy,
                                        StageTemplate.CleanupPolicy cleanupPolicy,
                                        StageTemplate.InteractionPolicy interactionPolicy) throws IOException {
        StageFlightAssets.Asset flight = session.hasFlight()
                ? StageFlightAssets.load(server, session.stageId(), session.flightHash()) : null;
        if (session.hasFlight() && flight == null) {
            throw new IOException("The active stage flight is unavailable");
        }
        net.minecraft.server.level.ServerLevel stageLevel = server.getLevel(StageWorlds.STG_STAGE);
        if (stageLevel == null) {
            throw new IOException("The stage dimension is unavailable");
        }
        CompoundTag arena = StageArenaSnapshot.capture(stageLevel, session.stageOrigin(), session.boundary());
        return new StageTemplate(id, session.lodPackId(), session.lodAnchor(), session.boundary(),
                session.clientScene(), session.capacity(), instanceMode, lifecyclePolicy,
                cleanupPolicy, interactionPolicy, flight == null ? new byte[0] : flight.sceneJson(), arena, "",
                compatibleEntryOffset(load(server, id), session.boundary()), java.util.List.of());
    }

    public static StageTemplate capture(MinecraftServer server, StageSession session,
                                        StageTemplateSummary summary) throws IOException {
        StageFlightAssets.Asset flight = session.hasFlight()
                ? StageFlightAssets.load(server, session.stageId(), session.flightHash()) : null;
        if (session.hasFlight() && flight == null) {
            throw new IOException("The active stage flight is unavailable");
        }
        net.minecraft.server.level.ServerLevel stageLevel = server.getLevel(StageWorlds.STG_STAGE);
        if (stageLevel == null) {
            throw new IOException("The stage dimension is unavailable");
        }
        CompoundTag arena = StageArenaSnapshot.capture(stageLevel, session.stageOrigin(), summary.boundary());
        return new StageTemplate(summary.id(), summary.lodPackId(), summary.lodAnchor(), summary.boundary(),
                summary.clientScene(), summary.capacity(), summary.instanceMode(), summary.lifecyclePolicy(),
                summary.cleanupPolicy(), summary.interactionPolicy(),
                flight == null ? new byte[0] : flight.sceneJson(), arena,
                flight == null ? "" : summary.flightName(),
                compatibleEntryOffset(load(server, summary.id()), summary.boundary()), java.util.List.of());
    }

    private static net.minecraft.core.BlockPos compatibleEntryOffset(StageTemplate existing,
                                                                       vibe.liteming.dynamicstage.stage.StageBoundary boundary) {
        if (existing == null || existing.entryOffset() == null) {
            return net.minecraft.core.BlockPos.ZERO;
        }
        return boundary.bounds(net.minecraft.core.BlockPos.ZERO).contains(existing.entryOffset().getX() + 0.5D,
                existing.entryOffset().getY(), existing.entryOffset().getZ() + 0.5D)
                ? existing.entryOffset() : net.minecraft.core.BlockPos.ZERO;
    }

    public static void save(StageTemplate template) throws IOException {
        save(rootDirectory(), arenaDirectory(), template);
    }

    static void save(Path root, StageTemplate template) throws IOException {
        save(root, siblingArenaDirectory(root), template);
    }

    static void save(Path root, Path arenas, StageTemplate template) throws IOException {
        validateTemplateId(template.id());
        root = root.toAbsolutePath().normalize();
        arenas = arenas.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Files.createDirectories(arenas);
        Path target = templateFile(root, template.id());
        String previousArena = readArenaReferenceQuietly(target);
        String arenaFile = template.hasArenaSnapshot() ? writeArena(arenas, template.arenaSnapshot()) : null;
        try {
            JsonObject json = StageTemplateJsonCodec.write(template, arenaFile);
            StageTemplate decoded = StageTemplateJsonCodec.parse(json, template.arenaSnapshot());
            if (!template.equals(decoded)) {
                throw new IOException("Stage template JSON does not preserve all template state: " + template.id());
            }
            byte[] manifest = (PRETTY_GSON.toJson(json)
                    + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
            if (manifest.length <= 0 || manifest.length > MAX_JSON_BYTES) {
                throw new IOException("Stage template JSON exceeds the supported size: " + template.id());
            }
            atomicWrite(root, target, manifest);
            StageTemplate reloaded = readFile(target, arenas);
            if (!template.equals(reloaded)) {
                throw new IOException("Stage template cannot be reloaded safely: " + template.id());
            }
            if (previousArena != null && !previousArena.equals(arenaFile)) {
                pruneArena(root, arenas, previousArena);
            }
        } catch (IOException | RuntimeException e) {
            if (arenaFile != null && !arenaFile.equals(previousArena)) {
                try {
                    pruneArena(root, arenas, arenaFile);
                } catch (IOException cleanupError) {
                    e.addSuppressed(cleanupError);
                }
            }
            throw e;
        }
    }

    @Nullable
    public static StageTemplate load(String id) throws IOException {
        validateTemplateId(id);
        return load(rootDirectory(), arenaDirectory(), id);
    }

    @Nullable
    public static StageTemplate load(MinecraftServer server, String id) throws IOException {
        StageTemplate local = load(id);
        return local != null ? local : StageDataTemplateStore.load(server).get(id);
    }

    @Nullable
    static StageTemplate load(Path root, String id) throws IOException {
        return load(root, siblingArenaDirectory(root), id);
    }

    @Nullable
    static StageTemplate load(Path root, Path arenas, String id) throws IOException {
        validateTemplateId(id);
        Path path = templateFile(root.toAbsolutePath().normalize(), id);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        return readFile(path, arenas.toAbsolutePath().normalize());
    }

    public static List<String> list() throws IOException {
        return listTemplates().stream().map(StageTemplate::id).toList();
    }

    public static List<StageTemplate> listTemplates() throws IOException {
        return listTemplates(rootDirectory(), arenaDirectory());
    }

    static List<StageTemplate> listTemplates(Path root, Path arenas) throws IOException {
        root = root.toAbsolutePath().normalize();
        arenas = arenas.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<StageTemplate> templates = new ArrayList<>();
        try (var files = Files.list(root)) {
            for (Path path : files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(StageTemplateStore::isTemplateJson).sorted().toList()) {
                try {
                    templates.add(readFile(path, arenas));
                } catch (IOException ignored) {
                    // Invalid files are reported by reload or explicit loading; listing remains usable.
                }
            }
        }
        return templates.stream().sorted(java.util.Comparator.comparing(StageTemplate::id)).toList();
    }

    public static List<StageTemplate> listTemplates(MinecraftServer server) throws IOException {
        Map<String, StageTemplate> templates = new LinkedHashMap<>(StageDataTemplateStore.load(server));
        for (StageTemplate template : listTemplates()) {
            templates.put(template.id(), template);
        }
        return templates.values().stream().sorted(java.util.Comparator.comparing(StageTemplate::id)).toList();
    }

    public static List<String> list(MinecraftServer server) throws IOException {
        return listTemplates(server).stream().map(StageTemplate::id).toList();
    }

    public static boolean delete(String id) throws IOException {
        return delete(rootDirectory(), arenaDirectory(), id);
    }

    static boolean delete(Path root, Path arenas, String id) throws IOException {
        validateTemplateId(id);
        root = root.toAbsolutePath().normalize();
        arenas = arenas.toAbsolutePath().normalize();
        Path target = templateFile(root, id);
        String arena = readArenaReferenceQuietly(target);
        boolean deleted = Files.deleteIfExists(target);
        if (deleted && arena != null) {
            pruneArena(root, arenas, arena);
        }
        return deleted;
    }

    /** Re-reads local JSON manifests and reports malformed templates or arena references. */
    public static ReloadResult reload() throws IOException {
        Path root = rootDirectory();
        Path arenas = arenaDirectory();
        if (!Files.isDirectory(root)) {
            return new ReloadResult(0, List.of());
        }
        int valid = 0;
        List<String> errors = new ArrayList<>();
        try (var files = Files.list(root)) {
            for (Path path : files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(StageTemplateStore::isTemplateJson).sorted().toList()) {
                try {
                    readFile(path, arenas);
                    valid++;
                } catch (IOException | RuntimeException e) {
                    errors.add(path.getFileName() + ": " + rootMessage(e));
                }
            }
        }
        return new ReloadResult(valid, List.copyOf(errors));
    }

    public static ReloadResult reload(MinecraftServer server) throws IOException {
        ReloadResult local = reload();
        int dataTemplates = StageDataTemplateStore.load(server).size();
        return new ReloadResult(local.templates() + dataTemplates, local.errors());
    }

    public static StageFlightAssets.Asset installFlight(MinecraftServer server, StageTemplate template)
            throws IOException {
        if (!template.hasFlight()) {
            return null;
        }
        return StageFlightAssets.install(server.getWorldPath(LevelResource.ROOT), template.id(),
                template.flightJson());
    }

    private static StageTemplate readFile(Path path, Path arenas) throws IOException {
        long size = Files.size(path);
        if (size <= 0L || size > MAX_JSON_BYTES) {
            throw new IOException("invalid template JSON size");
        }
        JsonObject json;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IOException("template root must be a JSON object");
            }
            json = root.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("invalid template JSON", e);
        }
        String arenaFile = arenaReference(json);
        CompoundTag arena = arenaFile == null ? new CompoundTag() : readArena(arenas, arenaFile);
        try {
            StageTemplate template = StageTemplateJsonCodec.parse(json, arena);
            if (!path.getFileName().toString().equals(template.id() + ".json")) {
                throw new IOException("template identity does not match its file name");
            }
            return template;
        } catch (RuntimeException e) {
            throw new IOException("invalid template fields", e);
        }
    }

    private static String writeArena(Path arenas, CompoundTag arena) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        NbtIo.writeCompressed(arena, output);
        byte[] compressed = output.toByteArray();
        if (compressed.length <= 0 || compressed.length > MAX_COMPRESSED_ARENA_BYTES) {
            throw new IOException("Stage arena snapshot exceeds the supported compressed size");
        }
        String fileName = ContentHash.sha256Hex(compressed) + ".nbt";
        Path target = arenaPath(arenas, fileName);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || Files.size(target) != compressed.length
                || !fileName.substring(0, 64).equals(ContentHash.sha256Hex(target))) {
            atomicWrite(arenas, target, compressed);
        }
        readArena(arenas, fileName);
        return fileName;
    }

    private static CompoundTag readArena(Path arenas, String fileName) throws IOException {
        Path path = arenaPath(arenas, fileName);
        if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("missing arena snapshot " + fileName);
        }
        long size = Files.size(path);
        if (size <= 0L || size > MAX_COMPRESSED_ARENA_BYTES) {
            throw new IOException("invalid arena snapshot size");
        }
        if (!fileName.substring(0, 64).equals(ContentHash.sha256Hex(path))) {
            throw new IOException("arena snapshot hash mismatch");
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(path))))) {
            CompoundTag tag = NbtIo.read(input, new NbtAccounter(MAX_ARENA_NBT_BYTES));
            if (tag == null) {
                throw new IOException("empty arena snapshot");
            }
            return tag;
        } catch (RuntimeException e) {
            throw new IOException("invalid arena snapshot", e);
        }
    }

    private static String arenaReference(JsonObject json) throws IOException {
        if (!json.has("arena") || json.get("arena").isJsonNull()) {
            return null;
        }
        String value = json.get("arena").getAsString();
        if (!ARENA_FILE.matcher(value).matches()) {
            throw new IOException("invalid arena snapshot reference");
        }
        return value;
    }

    private static String readArenaReferenceQuietly(Path manifest) {
        try {
            return readArenaReference(manifest);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String readArenaReference(Path manifest) throws IOException {
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonObject()) {
                throw new IOException("template root must be a JSON object");
            }
            return arenaReference(root.getAsJsonObject());
        } catch (RuntimeException e) {
            throw new IOException("invalid template JSON", e);
        }
    }

    private static void pruneArena(Path root, Path arenas, String arenaFile) throws IOException {
        if (!ARENA_FILE.matcher(arenaFile).matches() || !Files.isDirectory(root)) {
            return;
        }
        try (var files = Files.list(root)) {
            for (Path manifest : files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                    .filter(StageTemplateStore::isTemplateJson).toList()) {
                String referenced;
                try {
                    referenced = readArenaReference(manifest);
                } catch (IOException e) {
                    return;
                }
                if (arenaFile.equals(referenced)) {
                    return;
                }
            }
        }
        Files.deleteIfExists(arenaPath(arenas, arenaFile));
    }

    private static void atomicWrite(Path root, Path target, byte[] bytes) throws IOException {
        Files.createDirectories(root);
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Refusing to replace symbolic link " + target);
        }
        Path temporary = Files.createTempFile(root, target.getFileName().toString(), ".tmp");
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

    private static Path templateFile(Path root, String id) {
        validateTemplateId(id);
        return root.toAbsolutePath().normalize().resolve(id + ".json");
    }

    private static Path arenaPath(Path arenas, String fileName) throws IOException {
        if (!ARENA_FILE.matcher(fileName).matches()) {
            throw new IOException("invalid arena snapshot file name");
        }
        Path root = arenas.toAbsolutePath().normalize();
        Path path = root.resolve(fileName).normalize();
        if (!path.getParent().equals(root)) {
            throw new IOException("invalid arena snapshot path");
        }
        return path;
    }

    private static Path siblingArenaDirectory(Path root) {
        Path normalized = root.toAbsolutePath().normalize();
        Path parent = normalized.getParent();
        return (parent == null ? normalized : parent).resolve("arenas").toAbsolutePath().normalize();
    }

    private static boolean isTemplateJson(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".json") && TEMPLATE_ID.matcher(name.substring(0, name.length() - 5)).matches();
    }

    private static void validateTemplateId(String id) {
        if (id == null || !TEMPLATE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Template id must use 1-64 letters, digits, '_' or '-'");
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record ReloadResult(int templates, List<String> errors) {
    }
}
