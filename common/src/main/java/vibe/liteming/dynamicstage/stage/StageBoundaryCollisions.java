package vibe.liteming.dynamicstage.stage;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.Consumer;

/** Adds solid collision half-spaces without placing blocks in the stage world. */
public final class StageBoundaryCollisions {
    private static final double COLLISION_EXTENT = 30_000_000.0D;

    private StageBoundaryCollisions() {
    }

    public static void collect(Entity entity, AABB expandedBox, Consumer<VoxelShape> consumer) {
        StageBoundaryAccess.Located located = StageBoundaryAccess.find(entity);
        if (located == null) {
            return;
        }
        AABB bounds = located.boundary().bounds(located.origin());
        add(consumer, new AABB(-COLLISION_EXTENT, -COLLISION_EXTENT, -COLLISION_EXTENT,
                bounds.minX, COLLISION_EXTENT, COLLISION_EXTENT));
        add(consumer, new AABB(bounds.maxX, -COLLISION_EXTENT, -COLLISION_EXTENT,
                COLLISION_EXTENT, COLLISION_EXTENT, COLLISION_EXTENT));
        add(consumer, new AABB(bounds.minX, -COLLISION_EXTENT, -COLLISION_EXTENT,
                bounds.maxX, COLLISION_EXTENT, bounds.minZ));
        add(consumer, new AABB(bounds.minX, -COLLISION_EXTENT, bounds.maxZ,
                bounds.maxX, COLLISION_EXTENT, COLLISION_EXTENT));
        add(consumer, new AABB(bounds.minX, -COLLISION_EXTENT, bounds.minZ,
                bounds.maxX, bounds.minY, bounds.maxZ));
        add(consumer, new AABB(bounds.minX, bounds.maxY, bounds.minZ,
                bounds.maxX, COLLISION_EXTENT, bounds.maxZ));
    }

    private static void add(Consumer<VoxelShape> consumer, AABB box) {
        consumer.accept(Shapes.create(box));
    }
}
