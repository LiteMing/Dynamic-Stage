package vibe.liteming.dynamicstage.bake;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Locates Distant Horizons LOD data for a server's world. DH 2.x writes
 * {@code <world>/data/DistantHorizons.sqlite} (plus -wal/-shm when running);
 * older server-data dumps live under {@code DistantHorizons_server_data/}.
 */
public final class DHStorageLocator {

    private DHStorageLocator() {
    }

    @Nullable
    public static Path locate(MinecraftServer server) {
        Path root = server.getWorldPath(LevelResource.ROOT);
        Path direct = root.resolve("data").resolve("DistantHorizons.sqlite");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        // Server-data directory dumps (may be nested one level).
        Path serverData = root.resolve("DistantHorizons_server_data");
        if (Files.isDirectory(serverData)) {
            try (Stream<Path> files = Files.walk(serverData, 3)) {
                return files.filter(p -> p.getFileName().toString().equals("DistantHorizons.sqlite"))
                        .filter(Files::isRegularFile)
                        .findFirst()
                        .orElse(null);
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }
}
