package vibe.liteming.dynamicstage.bake;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Locates the most data-rich Voxy storage directory for a server's world:
 * scans {@code <world>/voxy/<hash>/storage} and picks the one with the largest
 * on-disk data (SST+log bytes). Cheap (no DB open).
 */
public final class VoxyStorageLocator {

    private VoxyStorageLocator() {
    }

    @Nullable
    public static Path locate(MinecraftServer server) {
        Path voxyRoot = server.getWorldPath(LevelResource.ROOT).resolve("voxy");
        if (!Files.isDirectory(voxyRoot)) {
            return null;
        }
        Path best = null;
        long bestBytes = -1L;
        try (Stream<Path> entries = Files.list(voxyRoot)) {
            for (Path storage : entries
                    .filter(Files::isDirectory)
                    .map(hashDir -> hashDir.resolve("storage"))
                    .filter(storage -> Files.exists(storage.resolve("CURRENT")))
                    .toList()) {
                long bytes = dataBytes(storage);
                if (bytes > bestBytes) {
                    bestBytes = bytes;
                    best = storage;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return best;
    }

    /** Total size of data files (.sst + .log) in a storage dir. */
    private static long dataBytes(Path storageDir) {
        try (Stream<Path> files = Files.list(storageDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(f -> {
                        String n = f.getFileName().toString();
                        return n.endsWith(".sst") || n.endsWith(".log");
                    })
                    .mapToLong(f -> {
                        try {
                            return Files.size(f);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        } catch (IOException e) {
            return 0L;
        }
    }
}
