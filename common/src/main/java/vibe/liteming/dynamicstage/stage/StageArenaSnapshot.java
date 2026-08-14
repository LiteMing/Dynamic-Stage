package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;

import java.io.IOException;

/** Vanilla structure snapshot for blocks, block entities, and non-player entities inside a stage boundary. */
public final class StageArenaSnapshot {
    public static final int MAX_HORIZONTAL_SIZE = 256;
    public static final int MAX_HEIGHT = 128;
    public static final long MAX_VOLUME = 4L * 1024L * 1024L;

    private StageArenaSnapshot() {
    }

    public static CompoundTag capture(ServerLevel level, BlockPos origin, StageBoundary boundary) throws IOException {
        validateSize(boundary);
        StructureTemplate structure = new StructureTemplate();
        structure.setAuthor("dynamicstage");
        structure.fillFromWorld(level, minimum(origin, boundary), size(boundary), true, null);
        return structure.save(new CompoundTag());
    }

    public static void restore(ServerLevel level, BlockPos origin, StageBoundary boundary,
                               CompoundTag snapshot) throws IOException {
        if (snapshot.isEmpty()) {
            return;
        }
        validateSize(boundary);
        StructureTemplate structure;
        try {
            structure = level.getServer().getStructureManager().readStructure(snapshot);
        } catch (RuntimeException e) {
            throw new IOException("invalid arena structure data", e);
        }
        if (!structure.getSize().equals(size(boundary))) {
            throw new IOException("arena structure size does not match the template boundary");
        }
        AABB bounds = boundary.bounds(origin);
        level.getEntities((Entity) null, bounds, entity -> !(entity instanceof Player)).forEach(Entity::discard);
        BlockPos minimum = minimum(origin, boundary);
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(false).setFinalizeEntities(true).setKeepLiquids(false);
        if (!structure.placeInWorld(level, minimum, minimum, settings, RandomSource.create(), Block.UPDATE_ALL)) {
            throw new IOException("the arena structure could not be placed");
        }
    }

    private static void validateSize(StageBoundary boundary) throws IOException {
        long volume = (long) boundary.width() * boundary.depth() * boundary.height();
        if (boundary.width() > MAX_HORIZONTAL_SIZE || boundary.depth() > MAX_HORIZONTAL_SIZE
                || boundary.height() > MAX_HEIGHT || volume > MAX_VOLUME) {
            throw new IOException("arena snapshot limit is " + MAX_HORIZONTAL_SIZE + " x "
                    + MAX_HORIZONTAL_SIZE + " x " + MAX_HEIGHT + " and " + MAX_VOLUME + " blocks");
        }
    }

    private static Vec3i size(StageBoundary boundary) {
        return new Vec3i(boundary.width(), boundary.height(), boundary.depth());
    }

    private static BlockPos minimum(BlockPos origin, StageBoundary boundary) {
        return origin.offset(-boundary.width() / 2, 0, -boundary.depth() / 2);
    }
}
