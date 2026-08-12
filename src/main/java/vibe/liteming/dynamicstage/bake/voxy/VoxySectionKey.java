package vibe.liteming.dynamicstage.bake.voxy;

/**
 * Voxy section key encoding (WorldEngine.getWorldSectionId, POS_FORMAT_VERSION=1):
 * {@code lvl<<60 | (y&0xFF)<<52 | (z&0xFFFFFF)<<28 | (x&0xFFFFFF)<<4}.
 * x/z are 24-bit signed, y is 8-bit signed; section size = 32 << lvl blocks.
 */
public final class VoxySectionKey {

    public static final int SECTION_BASE_SIZE = 32;
    public static final int POS_FORMAT_VERSION = 1;

    private VoxySectionKey() {
    }

    public static long encode(int lvl, int x, int y, int z) {
        return ((long) lvl << 60)
                | ((long) (y & 0xFF) << 52)
                | ((long) (z & 0xFFFFFF) << 28)
                | ((long) (x & 0xFFFFFF) << 4);
    }

    public static int levelOf(long key) {
        return (int) ((key >> 60) & 0xF);
    }

    public static int xOf(long key) {
        return (int) ((key << 36) >> 40); // signed 24-bit
    }

    public static int yOf(long key) {
        return (int) ((key << 4) >> 56);  // signed 8-bit
    }

    public static int zOf(long key) {
        return (int) ((key << 12) >> 40); // signed 24-bit
    }

    /** Block size of a section at the given level. */
    public static int sectionSize(int level) {
        return SECTION_BASE_SIZE << level;
    }

    /** Minimum block corner of a section. */
    public static long minBlockX(int level, int sectionX) {
        return (long) sectionX * sectionSize(level);
    }

    public static long minBlockY(int level, int sectionY) {
        return (long) sectionY * sectionSize(level);
    }

    public static long minBlockZ(int level, int sectionZ) {
        return (long) sectionZ * sectionSize(level);
    }
}
