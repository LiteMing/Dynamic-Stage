package vibe.liteming.dynamicstage.bake;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/** Locates Distant Horizons data in the current Minecraft dimension save folder. */
public final class DHStorageLocator {

    private static final ResourceLocation OVERWORLD = ResourceLocation.parse("minecraft:overworld");
    private static final ResourceLocation NETHER = ResourceLocation.parse("minecraft:the_nether");
    private static final ResourceLocation END = ResourceLocation.parse("minecraft:the_end");

    private DHStorageLocator() {
    }

    @Nullable
    public static Path locate(MinecraftServer server, ServerLevel sourceLevel) {
        Path dimensionRoot = dimensionRoot(server.getWorldPath(LevelResource.ROOT),
                sourceLevel.dimension().location());
        Path direct = dimensionRoot.resolve("data").resolve("DistantHorizons.sqlite");
        if (Files.isRegularFile(direct)) {
            return direct;
        }
        Path legacy = dimensionRoot.resolve("DistantHorizons_server_data");
        if (!Files.isDirectory(legacy)) {
            return null;
        }
        try (Stream<Path> files = Files.walk(legacy, 3)) {
            return files.filter(path -> path.getFileName().toString().equals("DistantHorizons.sqlite"))
                    .filter(Files::isRegularFile)
                    .findFirst()
                    .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public static Path dimensionRoot(Path worldRoot, ResourceLocation dimension) {
        if (dimension.equals(OVERWORLD)) {
            return worldRoot;
        }
        if (dimension.equals(NETHER)) {
            return worldRoot.resolve("DIM-1");
        }
        if (dimension.equals(END)) {
            return worldRoot.resolve("DIM1");
        }
        return worldRoot.resolve("dimensions").resolve(dimension.getNamespace()).resolve(dimension.getPath());
    }
}
