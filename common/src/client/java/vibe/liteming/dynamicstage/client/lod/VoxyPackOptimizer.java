package vibe.liteming.dynamicstage.client.lod;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/** Invokes the optional Dynamic Stage Voxy HDRS cache exporter without linking against Voxy. */
public final class VoxyPackOptimizer {
    private static final String OPTIMIZER = "me.cortex.voxy.common.stage.VoxyStageCacheOptimizer";

    private VoxyPackOptimizer() {
    }

    public static Result crop(LodPackRegistry.VoxyPack source, ResourceLocation outputId,
                              int minY, int maxY) throws IOException {
        return crop(source, outputId, minY, maxY, 0);
    }

    public static Result crop(LodPackRegistry.VoxyPack source, ResourceLocation outputId,
                              int minY, int maxY, int shellDepth) throws IOException {
        return crop(source, outputId, minY, maxY, shellDepth, 0, 0, -1);
    }

    public static Result crop(LodPackRegistry.VoxyPack source, ResourceLocation outputId,
                              int minY, int maxY, int shellDepth, int anchorX, int anchorZ,
                              int horizontalRadius) throws IOException {
        if (minY > maxY) {
            throw new IOException("Minimum Y must not exceed maximum Y");
        }
        if (shellDepth < 0 || shellDepth > 16) {
            throw new IOException("Shell depth must be between 0 and 16");
        }
        if (horizontalRadius == 0 || horizontalRadius < -1) {
            throw new IOException("Horizontal radius must be positive or -1 for unlimited");
        }
        Path packageRoot = LodPackRegistry.rootDirectory();
        Path outputDirectory = packageRoot.resolve(outputId.getNamespace()).resolve(outputId.getPath())
                .toAbsolutePath().normalize();
        if (!outputDirectory.startsWith(packageRoot) || Files.exists(outputDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Optimized LOD package already exists or is unsafe: " + outputId);
        }

        Path outputStorage = outputDirectory.resolve("voxy").resolve(source.worldId()).resolve("storage")
                .normalize();
        if (!outputStorage.startsWith(outputDirectory)) {
            throw new IOException("Optimized Voxy storage path is unsafe");
        }
        try {
            Files.createDirectories(outputStorage.getParent());
            Object report = invokeCrop(source.storageDirectory(), outputStorage, minY, maxY, shellDepth,
                    anchorX, anchorZ, horizontalRadius);
            writeManifest(outputDirectory, source.worldId());
            LodPackRegistry.Pack loaded = LodPackRegistry.load(packageRoot, outputId);
            if (!(loaded instanceof LodPackRegistry.VoxyPack pack)) {
                throw new IOException("Optimized package is not Voxy");
            }
            return new Result(pack, minY, maxY, longValue(report, "sourceLevelZeroSections"),
                    longValue(report, "outputVoxelizedSections"), longValue(report, "sourceNonAirBlocks"),
                    longValue(report, "retainedNonAirBlocks"), longValue(report, "removedNonAirBlocks"),
                    longValue(report, "collapsedNonAirBlocks"), longValue(report, "radiusRemovedNonAirBlocks"),
                    longValue(report, "outputBytes"));
        } catch (Throwable error) {
            deleteOutput(packageRoot, outputDirectory);
            if (error instanceof IOException io) {
                throw io;
            }
            throw new IOException("Could not optimize Voxy LOD package", rootCause(error));
        }
    }

    private static Object invokeCrop(Path source, Path destination, int minY, int maxY, int shellDepth,
                                     int anchorX, int anchorZ, int horizontalRadius) throws Exception {
        Class<?> optimizer;
        try {
            optimizer = Class.forName(OPTIMIZER);
        } catch (ClassNotFoundException e) {
            throw new IOException("Voxy HDRS with Dynamic Stage optimizer support is required", e);
        }
        try {
            Method crop = optimizer.getMethod("crop", Path.class, Path.class, int.class, int.class,
                    int.class, int.class, int.class, int.class, int.class);
            return crop.invoke(null, source, destination, minY, maxY, 9, shellDepth,
                    anchorX, anchorZ, horizontalRadius);
        } catch (NoSuchMethodException e) {
            throw new IOException("Installed Voxy does not provide the Dynamic Stage cache optimizer", e);
        } catch (InvocationTargetException e) {
            Throwable cause = rootCause(e);
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IOException("Voxy cache optimizer failed", cause);
        }
    }

    private static void writeManifest(Path outputDirectory, String worldId) throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("formatVersion", LodPackRegistry.FORMAT_VERSION);
        manifest.addProperty("backend", "voxy");
        manifest.addProperty("minecraftVersion", "1.20.1");
        manifest.addProperty("voxyVersion", LodPackRegistry.VOXY_VERSION);
        manifest.addProperty("worldId", worldId);
        manifest.addProperty("minY", LodPackRegistry.STAGE_MIN_Y);
        manifest.addProperty("height", LodPackRegistry.STAGE_HEIGHT);
        Files.writeString(outputDirectory.resolve("manifest.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(manifest) + System.lineSeparator());
    }

    private static long longValue(Object report, String name) throws IOException {
        try {
            Object value = report.getClass().getMethod(name).invoke(report);
            if (value instanceof Number number) {
                return number.longValue();
            }
            throw new IOException("Voxy optimizer returned invalid " + name);
        } catch (ReflectiveOperationException e) {
            throw new IOException("Voxy optimizer result is incompatible", e);
        }
    }

    private static void deleteOutput(Path root, Path output) {
        if (!output.startsWith(root) || !Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(output, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public record Result(LodPackRegistry.VoxyPack pack, int minY, int maxY,
                         long sourceLevelZeroSections, long outputVoxelizedSections,
                         long sourceNonAirBlocks, long retainedNonAirBlocks,
                         long removedNonAirBlocks, long collapsedNonAirBlocks,
                         long radiusRemovedNonAirBlocks, long outputBytes) {
    }
}
