package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.phys.AABB;
import vibe.liteming.dynamicstage.template.StageStructurePlacement;

import java.io.IOException;
import java.util.List;

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
        structure.fillFromWorld(level, minimum(origin, boundary), size(boundary), true, Blocks.AIR);
        return structure.save(new CompoundTag());
    }

    public static void restore(ServerLevel level, BlockPos origin, StageBoundary boundary,
                               CompoundTag snapshot) throws IOException {
        restore(level, origin, boundary, snapshot, List.of());
    }

    public static void restore(ServerLevel level, BlockPos origin, StageBoundary boundary,
                               CompoundTag snapshot, List<StageStructurePlacement> structures) throws IOException {
        StructureTemplate structure = snapshot.isEmpty() ? null : read(level, boundary, snapshot);
        List<ResolvedStructure> resolved = resolveStructures(level, origin, boundary, structures);
        AABB region = regionBounds(level, origin);
        LoquatArenaCompat.clearAreas(level, region);
        clearBoundary(level, origin, boundary);
        discardNonPlayers(level, region);
        if (structure != null) {
            place(level, origin, boundary, structure);
        }
        for (ResolvedStructure placement : resolved) {
            place(level, placement.position(), placement.structure());
        }
    }

    /** Releases every resource owned by an empty, non-persistent instance slot. */
    public static void release(ServerLevel level, BlockPos origin, StageBoundary boundary) throws IOException {
        AABB region = regionBounds(level, origin);
        LoquatArenaCompat.clearAreas(level, region);
        clearBoundary(level, origin, boundary);
        discardNonPlayers(level, region);
    }

    public static void validate(ServerLevel level, StageBoundary boundary, CompoundTag snapshot) throws IOException {
        validate(level, boundary, snapshot, List.of());
    }

    public static void validate(ServerLevel level, StageBoundary boundary, CompoundTag snapshot,
                                List<StageStructurePlacement> structures) throws IOException {
        if (!snapshot.isEmpty()) {
            read(level, boundary, snapshot);
        }
        resolveStructures(level, BlockPos.ZERO, boundary, structures);
    }

    /** Replaces a live arena and removes blocks left outside a smaller new boundary. */
    public static void replace(ServerLevel level, BlockPos origin, StageBoundary previousBoundary,
                               StageBoundary boundary, CompoundTag snapshot) throws IOException {
        replace(level, origin, previousBoundary, boundary, snapshot, List.of());
    }

    public static void replace(ServerLevel level, BlockPos origin, StageBoundary previousBoundary,
                               StageBoundary boundary, CompoundTag snapshot,
                               List<StageStructurePlacement> structures) throws IOException {
        StructureTemplate structure = snapshot.isEmpty() ? null : read(level, boundary, snapshot);
        List<ResolvedStructure> resolved = resolveStructures(level, origin, boundary, structures);
        AABB previousBounds = previousBoundary.bounds(origin);
        AABB nextBounds = boundary.bounds(origin);
        AABB affected = new AABB(Math.min(previousBounds.minX, nextBounds.minX),
                Math.min(previousBounds.minY, nextBounds.minY), Math.min(previousBounds.minZ, nextBounds.minZ),
                Math.max(previousBounds.maxX, nextBounds.maxX), Math.max(previousBounds.maxY, nextBounds.maxY),
                Math.max(previousBounds.maxZ, nextBounds.maxZ));
        LoquatArenaCompat.clearAreas(level, affected);
        clearPreviousRemainder(level, origin, previousBoundary, boundary);
        clearBoundary(level, origin, boundary);
        discardNonPlayers(level, affected);
        if (structure != null) {
            place(level, origin, boundary, structure);
        }
        for (ResolvedStructure placement : resolved) {
            place(level, placement.position(), placement.structure());
        }
    }

    private static StructureTemplate read(ServerLevel level, StageBoundary boundary,
                                          CompoundTag snapshot) throws IOException {
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
        return structure;
    }

    private static void place(ServerLevel level, BlockPos origin, StageBoundary boundary,
                              StructureTemplate structure) throws IOException {
        BlockPos minimum = minimum(origin, boundary);
        place(level, minimum, structure);
    }

    private static void place(ServerLevel level, BlockPos position, StructureTemplate structure) throws IOException {
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .setIgnoreEntities(false).setFinalizeEntities(true).setKeepLiquids(false);
        if (!structure.placeInWorld(level, position, position, settings, RandomSource.create(), Block.UPDATE_ALL)) {
            throw new IOException("the arena structure could not be placed");
        }
    }

    private static List<ResolvedStructure> resolveStructures(ServerLevel level, BlockPos origin,
                                                              StageBoundary boundary,
                                                              List<StageStructurePlacement> placements)
            throws IOException {
        validateSize(boundary);
        AABB bounds = boundary.bounds(origin);
        java.util.ArrayList<ResolvedStructure> resolved = new java.util.ArrayList<>();
        for (StageStructurePlacement placement : placements) {
            StructureTemplate structure = level.getStructureManager().get(placement.structureId())
                    .orElseThrow(() -> new IOException("missing stage structure " + placement.structureId()));
            BlockPos position = origin.offset(placement.offset());
            Vec3i size = structure.getSize();
            if (position.getX() < bounds.minX || position.getY() < bounds.minY || position.getZ() < bounds.minZ
                    || position.getX() + size.getX() > bounds.maxX
                    || position.getY() + size.getY() > bounds.maxY
                    || position.getZ() + size.getZ() > bounds.maxZ) {
                throw new IOException("stage structure " + placement.structureId() + " is outside the boundary");
            }
            resolved.add(new ResolvedStructure(structure, position));
        }
        return List.copyOf(resolved);
    }

    private static void clearPreviousRemainder(ServerLevel level, BlockPos origin,
                                               StageBoundary previousBoundary, StageBoundary boundary) {
        BlockPos previousMinimum = minimum(origin, previousBoundary);
        BlockPos nextMinimum = minimum(origin, boundary);
        int nextMaxX = nextMinimum.getX() + boundary.width();
        int nextMaxY = nextMinimum.getY() + boundary.height();
        int nextMaxZ = nextMinimum.getZ() + boundary.depth();
        int previousMaxX = previousMinimum.getX() + previousBoundary.width();
        int previousMaxY = previousMinimum.getY() + previousBoundary.height();
        int previousMaxZ = previousMinimum.getZ() + previousBoundary.depth();
        if (previousMinimum.getX() >= nextMinimum.getX() && previousMaxX <= nextMaxX
                && previousMinimum.getY() >= nextMinimum.getY() && previousMaxY <= nextMaxY
                && previousMinimum.getZ() >= nextMinimum.getZ() && previousMaxZ <= nextMaxZ) {
            return;
        }
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;
        for (int x = previousMinimum.getX(); x < previousMaxX; x++) {
            for (int y = previousMinimum.getY(); y < previousMaxY; y++) {
                for (int z = previousMinimum.getZ(); z < previousMaxZ; z++) {
                    if (x >= nextMinimum.getX() && x < nextMaxX
                            && y >= nextMinimum.getY() && y < nextMaxY
                            && z >= nextMinimum.getZ() && z < nextMaxZ) {
                        continue;
                    }
                    cursor.set(x, y, z);
                    if (!level.isEmptyBlock(cursor)) {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), flags);
                    }
                }
            }
        }
    }

    private static void clearBoundary(ServerLevel level, BlockPos origin, StageBoundary boundary) {
        BlockPos minimum = minimum(origin, boundary);
        clearBox(level, minimum, minimum.getX() + boundary.width(),
                minimum.getY() + boundary.height(), minimum.getZ() + boundary.depth());
    }

    private static void discardNonPlayers(ServerLevel level, AABB bounds) {
        List<Entity> entities = level.getEntities((Entity) null, bounds, entity -> !(entity instanceof Player));
        entities.forEach(StageArenaSnapshot::discardEntityTree);
    }

    private static void discardEntityTree(Entity entity) {
        if (entity.isRemoved() || entity instanceof Player) {
            return;
        }
        for (Entity passenger : List.copyOf(entity.getPassengers())) {
            if (passenger instanceof Player) {
                passenger.stopRiding();
            } else {
                discardEntityTree(passenger);
            }
        }
        entity.discard();
    }

    private static AABB regionBounds(ServerLevel level, BlockPos origin) {
        return StagePlacement.regionBounds(origin, level.getMinBuildHeight(), level.getMaxBuildHeight());
    }

    private static void clearBox(ServerLevel level, BlockPos minimum, int maxX, int maxY, int maxZ) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_SUPPRESS_DROPS;
        for (int x = minimum.getX(); x < maxX; x++) {
            for (int y = minimum.getY(); y < maxY; y++) {
                for (int z = minimum.getZ(); z < maxZ; z++) {
                    cursor.set(x, y, z);
                    if (!level.isEmptyBlock(cursor)) {
                        level.setBlock(cursor, Blocks.AIR.defaultBlockState(), flags);
                    }
                }
            }
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

    private record ResolvedStructure(StructureTemplate structure, BlockPos position) {
    }
}
