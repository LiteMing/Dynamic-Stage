package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Instance-local rectangular play area and its client grid tint. */
public record StageBoundary(int width, int depth, int height, int color) {
    public static final int DEFAULT_WIDTH = 60;
    public static final int DEFAULT_DEPTH = 60;
    public static final int DEFAULT_HEIGHT = 18;
    public static final int DEFAULT_COLOR = 0xFF4858;
    public static final int UNSET_COLOR = -1;
    public static final int MIN_HORIZONTAL_SIZE = 4;
    public static final int MAX_HORIZONTAL_SIZE = StagePlacement.REGION_SPACING - 64;
    public static final int MIN_HEIGHT = 2;
    public static final int MAX_HEIGHT = 240;

    public StageBoundary {
        if (width < MIN_HORIZONTAL_SIZE || width > MAX_HORIZONTAL_SIZE) {
            throw new IllegalArgumentException("Invalid stage boundary width: " + width);
        }
        if (depth < MIN_HORIZONTAL_SIZE || depth > MAX_HORIZONTAL_SIZE) {
            throw new IllegalArgumentException("Invalid stage boundary depth: " + depth);
        }
        if (height < MIN_HEIGHT || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("Invalid stage boundary height: " + height);
        }
        if (color != UNSET_COLOR && (color < 0 || color > 0xFFFFFF)) {
            throw new IllegalArgumentException("Invalid stage boundary color: " + color);
        }
    }

    public static StageBoundary defaults() {
        return new StageBoundary(DEFAULT_WIDTH, DEFAULT_DEPTH, DEFAULT_HEIGHT, UNSET_COLOR);
    }

    public StageBoundary withColor(int newColor) {
        return new StageBoundary(width, depth, height, newColor);
    }

    /** The floor is at the instance origin Y and the ceiling is {@code height} blocks above it. */
    public AABB bounds(BlockPos origin) {
        double halfWidth = width * 0.5D;
        double halfDepth = depth * 0.5D;
        return new AABB(origin.getX() - halfWidth, origin.getY(), origin.getZ() - halfDepth,
                origin.getX() + halfWidth, origin.getY() + height, origin.getZ() + halfDepth);
    }

    public Vec3 clampPlayer(BlockPos origin, Vec3 position, float playerWidth, float playerHeight) {
        AABB bounds = bounds(origin);
        double horizontalMargin = playerWidth * 0.5D + 0.01D;
        return new Vec3(
                clamp(position.x, bounds.minX + horizontalMargin, bounds.maxX - horizontalMargin),
                clamp(position.y, bounds.minY, bounds.maxY - playerHeight - 0.01D),
                clamp(position.z, bounds.minZ + horizontalMargin, bounds.maxZ - horizontalMargin)
        );
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Width", width);
        tag.putInt("Depth", depth);
        tag.putInt("Height", height);
        if (color != UNSET_COLOR) {
            tag.putInt("Color", color);
        }
        return tag;
    }

    public static StageBoundary load(CompoundTag tag) {
        return new StageBoundary(tag.getInt("Width"), tag.getInt("Depth"), tag.getInt("Height"),
                tag.contains("Color", Tag.TAG_INT) ? tag.getInt("Color") : UNSET_COLOR);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
