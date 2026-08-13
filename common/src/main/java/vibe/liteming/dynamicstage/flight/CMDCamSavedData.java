package vibe.liteming.dynamicstage.flight;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;

/** Reads CMDCam's world-local SavedData without linking against CMDCam classes. */
final class CMDCamSavedData {
    private static final long MAX_COMPRESSED_BYTES = 4L * 1024L * 1024L;
    private static final long MAX_NBT_BYTES = 16L * 1024L * 1024L;

    private CMDCamSavedData() {
    }

    static List<String> listScenes(Path file) throws IOException {
        CompoundTag scenes = readScenes(file);
        List<String> names = new ArrayList<>();
        for (String key : scenes.getAllKeys()) {
            if (scenes.contains(key, Tag.TAG_COMPOUND)) {
                names.add(key);
            }
        }
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }

    static JsonObject readScene(Path file, String name) throws IOException {
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new IOException("Invalid CMDCam scene name");
        }
        CompoundTag scenes = readScenes(file);
        if (!scenes.contains(name, Tag.TAG_COMPOUND)) {
            throw new IOException("CMDCam scene not found: " + name);
        }
        return toJsonObject(scenes.getCompound(name));
    }

    static List<String> listLiveScenes(ServerLevel level) {
        try {
            Class<?> bridge = Class.forName("team.creative.cmdcam.server.CMDCamServer");
            Object result = bridge.getMethod("getSavedPaths", net.minecraft.world.level.Level.class)
                    .invoke(null, level);
            if (!(result instanceof java.util.Collection<?> values)) {
                return List.of();
            }
            return values.stream().filter(String.class::isInstance).map(String.class::cast)
                    .sorted().toList();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
            return List.of();
        }
    }

    static JsonObject readLiveScene(ServerLevel level, String name) throws IOException {
        if (name == null || name.isBlank() || name.length() > 128) {
            throw new IOException("Invalid CMDCam scene name");
        }
        try {
            Class<?> bridge = Class.forName("team.creative.cmdcam.server.CMDCamServer");
            Object scene = bridge.getMethod("get", net.minecraft.world.level.Level.class, String.class)
                    .invoke(null, level, name);
            if (scene == null) {
                return null;
            }
            CompoundTag serialized = (CompoundTag) scene.getClass().getMethod("save", CompoundTag.class)
                    .invoke(scene, new CompoundTag());
            return toJsonObject(serialized);
        } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
            return null;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            throw new IOException("Could not read live CMDCam scene '" + name + "'", e);
        }
    }

    private static CompoundTag readScenes(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new IOException("CMDCam has not saved any scenes in this world (checked " + file + ")");
        }
        long size = Files.size(file);
        if (size <= 0L || size > MAX_COMPRESSED_BYTES) {
            throw new IOException("CMDCam scene database exceeds the supported size");
        }
        byte[] bytes = Files.readAllBytes(file);
        boolean gzip = bytes.length >= 2 && (bytes[0] & 0xFF) == 0x1F && (bytes[1] & 0xFF) == 0x8B;
        try (DataInputStream input = gzip
                ? new DataInputStream(new BufferedInputStream(
                        new GZIPInputStream(new ByteArrayInputStream(bytes))))
                : new DataInputStream(new ByteArrayInputStream(bytes))) {
            CompoundTag root = NbtIo.read(input, new NbtAccounter(MAX_NBT_BYTES));
            if (root == null) {
                throw new IOException("CMDCam scene database is empty");
            }
            return root.contains("data", Tag.TAG_COMPOUND) ? root.getCompound("data") : root;
        } catch (RuntimeException e) {
            throw new IOException("Invalid CMDCam scene database", e);
        }
    }

    private static JsonObject toJsonObject(CompoundTag compound) throws IOException {
        JsonObject json = new JsonObject();
        for (String key : compound.getAllKeys()) {
            json.add(key, toJson(compound.get(key)));
        }
        return json;
    }

    private static com.google.gson.JsonElement toJson(Tag tag) throws IOException {
        if (tag instanceof CompoundTag compound) {
            return toJsonObject(compound);
        }
        if (tag instanceof ListTag list) {
            JsonArray json = new JsonArray();
            for (Tag element : list) {
                json.add(toJson(element));
            }
            return json;
        }
        if (tag instanceof NumericTag numeric) {
            return switch (tag.getId()) {
                case Tag.TAG_BYTE -> new JsonPrimitive(numeric.getAsByte());
                case Tag.TAG_SHORT -> new JsonPrimitive(numeric.getAsShort());
                case Tag.TAG_INT -> new JsonPrimitive(numeric.getAsInt());
                case Tag.TAG_LONG -> new JsonPrimitive(numeric.getAsLong());
                case Tag.TAG_FLOAT -> new JsonPrimitive(numeric.getAsFloat());
                case Tag.TAG_DOUBLE -> new JsonPrimitive(numeric.getAsDouble());
                default -> throw new IOException("Unsupported CMDCam numeric NBT type: " + tag.getId());
            };
        }
        if (tag.getId() == Tag.TAG_STRING) {
            return new JsonPrimitive(tag.getAsString());
        }
        throw new IOException("Unsupported CMDCam NBT type: " + tag.getId());
    }
}
