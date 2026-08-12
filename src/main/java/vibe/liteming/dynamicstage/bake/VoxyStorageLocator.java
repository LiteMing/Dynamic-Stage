package vibe.liteming.dynamicstage.bake;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.storage.LevelResource;
import vibe.liteming.dynamicstage.backdrop.BackdropProducts;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resolves the exact Voxy storage for the current dimension and biome seed. */
public final class VoxyStorageLocator {

    private VoxyStorageLocator() {
    }

    @Nullable
    public static Path locate(MinecraftServer server, ServerLevel sourceLevel) {
        String worldId = worldId(BiomeManager.obfuscateSeed(sourceLevel.getSeed()),
                sourceLevel.dimension().toString());
        Path storage = server.getWorldPath(LevelResource.ROOT)
                .resolve("voxy").resolve(worldId).resolve("storage");
        return Files.isRegularFile(storage.resolve("CURRENT")) ? storage : null;
    }

    /** Mirrors Voxy {@code WorldIdentifier.getWorldId}; the seed is already obfuscated. */
    public static String worldId(long biomeSeed, String dimensionKey) {
        String data = biomeSeed + dimensionKey;
        return BackdropProducts.sha256Hex(data.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }
}
