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
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Atomic storage for portable stage templates under the game/server config directory. */
public final class StageTemplateStore {
    private static final long MAX_COMPRESSED_BYTES = 32L * 1024L * 1024L;
    private static final long MAX_NBT_BYTES = 128L * 1024L * 1024L;

    private StageTemplateStore() {
    }

    public static Path rootDirectory() {
        return StagePlatform.configDirectory().resolve("dynamicstage").resolve("templates")
                .toAbsolutePath().normalize();
    }

    public static StageTemplate capture(MinecraftServer server, StageSession session, String id,
                                        StageTemplate.InstanceMode instanceMode,
                                        StageTemplate.ResetPolicy resetPolicy) throws IOException {
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
                session.clientScene(), session.capacity(), instanceMode, resetPolicy,
                flight == null ? new byte[0] : flight.sceneJson(), arena);
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
        return load(rootDirectory(), id);
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
        Path root = rootDirectory();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try (var files = Files.list(root)) {
            for (Path path : files.filter(Files::isRegularFile).filter(file -> file.getFileName().toString()
                    .endsWith(".dat")).sorted().toList()) {
                try {
                    StageTemplate template = readFile(path);
                    ids.add(template.id());
                } catch (IOException ignored) {
                    // Invalid files are reported when explicitly loaded; listing remains usable.
                }
            }
        }
        return ids.stream().sorted().toList();
    }

    public static boolean delete(String id) throws IOException {
        return Files.deleteIfExists(file(rootDirectory(), id));
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
}
