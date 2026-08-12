package vibe.liteming.dynamicstage.backdrop;

/**
 * A single vertical span column in the baked backdrop payload.
 * <p>
 * Encoded as x/z (short each), yStart/yEnd (short each), paletteIndex (varint),
 * lodLevel (byte). Coordinates are relative to {@link BackdropManifest} anchor.
 */
public record BackdropColumn(int x, int z, int yStart, int yEnd, int paletteIndex, int lodLevel) {

    public BackdropColumn {
        if (yEnd < yStart) {
            throw new IllegalArgumentException("yEnd < yStart: " + yEnd + " < " + yStart);
        }
        if (paletteIndex < 0) {
            throw new IllegalArgumentException("paletteIndex must be >= 0, got " + paletteIndex);
        }
        if (lodLevel < 0) {
            throw new IllegalArgumentException("lodLevel must be >= 0, got " + lodLevel);
        }
    }
}
