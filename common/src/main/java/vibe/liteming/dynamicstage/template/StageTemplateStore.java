package vibe.liteming.dynamicstage.template;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jetbrains.annotations.Nullable;
import vibe.liteming.dynamicstage.flight.StageFlightAssets;
import vibe.liteming.dynamicstage.platform.StagePlatform;
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.stage.StageArenaSnapshot;
import vibe.liteming.dynamicstage.world.StageWorlds;
import vibe.liteming.dynamicstage.util.ContentHash;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Atomic storage for portable stage templates in the copyable Dynamic Stage resource directory. */
public final class StageTemplateStore {
    private static final long MAX_COMPRESSED_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_NBT_BYTES = 128L * 1024L * 1024L;

    private StageTemplateStore() {
    }

    public static Path rootDirectory() {
        return StagePlatform.gameDirectory().resolve("dynamicstage").resolve("templates")
                .toAbsolutePath().normalize();
    }

    public static Path legacyRootDirectory() {
        return StagePlatform.configDirectory().resolve("dynamicstage").resolve("templates")
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
        save(rootDirectory(), template);
    }

    static void save(Path root, StageTemplate template) throws IOException {
        Files.createDirectories(root);
        Path target = file(root, template.id());
        Path temporary = Files.createTempFile(root, target.getFileName().toString(), ".tmp");
        try {
            try (var output = Files.newOutputStream(temporary)) {
                NbtIo.writeCompressed(template.save(), output);
            }
            try {
                readFile(temporary);
            } catch (IOException e) {
                throw new IOException("Stage template cannot be reloaded safely: " + e.getMessage(), e);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Nullable
    public static StageTemplate load(String id) throws IOException {
        migrateLegacyTemplates();
        StageTemplate template = load(rootDirectory(), id);
        return template != null ? template : load(legacyRootDirectory(), id);
    }

    @Nullable
    public static StageTemplate load(MinecraftServer server, String id) throws IOException {
        StageTemplate local = load(id);
        return local != null ? local : StageDataTemplateStore.load(server).get(id);
    }

    @Nullable
    static StageTemplate load(Path root, String id) throws IOException {
        Path path = file(root, id);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        long size = Files.size(path);
        if (size <= 0L || size > MAX_COMPRESSED_BYTES) {
            throw new IOException("Stage template exceeds the supported size: " + id);
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(path))))) {
            CompoundTag tag = NbtIo.read(input, new NbtAccounter(MAX_NBT_BYTES));
            if (tag == null) {
                throw new IOException("Stage template is empty: " + id);
            }
            StageTemplate template = StageTemplate.load(tag);
            if (!template.id().equals(id)) {
                throw new IOException("Stage template identity mismatch: " + id);
            }
            return template;
        } catch (RuntimeException e) {
            throw new IOException("Invalid stage template '" + id + "': " + e.getMessage(), e);
        }
    }

    public static List<String> list() throws IOException {
        return listTemplates().stream().map(StageTemplate::id).toList();
    }

    public static List<StageTemplate> listTemplates() throws IOException {
        migrateLegacyTemplates();
        Path root = rootDirectory();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<StageTemplate> templates = new ArrayList<>();
        try (var files = Files.list(root)) {
            for (Path path : files.filter(Files::isRegularFile).filter(file -> file.getFileName().toString()
                    .endsWith(".dat")).sorted().toList()) {
                try {
                    StageTemplate template = readFile(path);
                    templates.add(template);
                } catch (IOException ignored) {
                    // Invalid files are reported when explicitly loaded; listing remains usable.
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
        boolean deleted = Files.deleteIfExists(file(rootDirectory(), id));
        Path legacy = legacyRootDirectory();
        if (!legacy.equals(rootDirectory())) {
            deleted |= Files.deleteIfExists(file(legacy, id));
        }
        return deleted;
    }

    /** Re-runs legacy migration and reports invalid portable template files. */
    public static ReloadResult reload() throws IOException {
        int migrated = migrateLegacyTemplates();
        Path root = rootDirectory();
        if (!Files.isDirectory(root)) {
            return new ReloadResult(0, migrated, List.of());
        }
        int valid = 0;
        List<String> errors = new ArrayList<>();
        try (var files = Files.list(root)) {
            for (Path path : files.filter(Files::isRegularFile).filter(file -> file.getFileName().toString()
                    .endsWith(".dat")).sorted().toList()) {
                try {
                    readFile(path);
                    valid++;
                } catch (IOException e) {
                    errors.add(path.getFileName() + ": " + e.getMessage());
                }
            }
        }
        return new ReloadResult(valid, migrated, List.copyOf(errors));
    }

    public static ReloadResult reload(MinecraftServer server) throws IOException {
        ReloadResult local = reload();
        int dataTemplates = StageDataTemplateStore.load(server).size();
        return new ReloadResult(local.templates() + dataTemplates, local.migratedTemplates(), local.errors());
    }

    private static synchronized int migrateLegacyTemplates() throws IOException {
        return migrate(legacyRootDirectory(), rootDirectory());
    }

    static int migrate(Path legacyRoot, Path targetRoot) throws IOException {
        Path legacy = legacyRoot.toAbsolutePath().normalize();
        Path target = targetRoot.toAbsolutePath().normalize();
        if (legacy.equals(target) || !Files.isDirectory(legacy)) {
            return 0;
        }
        Map<String, StageTemplate> templates = new LinkedHashMap<>();
        try (var files = Files.list(legacy)) {
            for (Path path : files.filter(Files::isRegularFile).filter(file -> file.getFileName().toString()
                    .endsWith(".dat")).sorted().toList()) {
                try {
                    StageTemplate template = readFile(path);
                    templates.putIfAbsent(template.id(), template);
                } catch (IOException ignored) {
                    // Invalid legacy files remain untouched and are not migrated.
                }
            }
        }
        int migrated = 0;
        for (StageTemplate template : templates.values()) {
            if (!Files.isRegularFile(file(target, template.id()))) {
                save(target, template);
                migrated++;
            }
        }
        return migrated;
    }

    public static StageFlightAssets.Asset installFlight(MinecraftServer server, StageTemplate template)
            throws IOException {
        if (!template.hasFlight()) {
            return null;
        }
        return StageFlightAssets.install(server.getWorldPath(LevelResource.ROOT), template.id(),
                template.flightJson());
    }

    private static StageTemplate readFile(Path path) throws IOException {
        long size = Files.size(path);
        if (size <= 0L || size > MAX_COMPRESSED_BYTES) {
            throw new IOException("invalid template file size");
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(path))))) {
            CompoundTag tag = NbtIo.read(input, new NbtAccounter(MAX_NBT_BYTES));
            if (tag == null) {
                throw new IOException("empty template");
            }
            return StageTemplate.load(tag);
        } catch (RuntimeException e) {
            throw new IOException("invalid template", e);
        }
    }

    private static Path file(Path root, String id) {
        if (id == null || id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("Invalid template id");
        }
        String hash = ContentHash.sha256Hex(id.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
        return root.toAbsolutePath().normalize().resolve(hash + ".dat");
    }

    public record ReloadResult(int templates, int migratedTemplates, List<String> errors) {
    }
}
