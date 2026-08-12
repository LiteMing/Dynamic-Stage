package vibe.liteming.dynamicstage.bake;

/**
 * Uniform column emitted by {@link LodProvider}s. Y coordinates are absolute
 * world heights; color is ARGB (alpha ignored by the renderer).
 */
public record LodColumn(int x, int z, int yStart, int yEnd, int colorArgb, int lodLevel) {

    public LodColumn {
        if (yEnd < yStart) {
            throw new IllegalArgumentException("yEnd < yStart: " + yEnd + " < " + yStart);
        }
        if (lodLevel < 0) {
            throw new IllegalArgumentException("lodLevel must be >= 0, got " + lodLevel);
        }
    }
}
