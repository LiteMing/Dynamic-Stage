package vibe.liteming.dynamicstage.bake.lod;

/**
 * Single source-world voxel shared by LOD readers, the portable baker, and the
 * client backdrop mesh. Coordinates are absolute source-world block positions
 * until the downloaded Blob is projected into a stage region.
 * <p>
 * {@code size} is the XZ block extent of the cell (1 for fine levels); for
 * coarse DH levels a voxel may cover several blocks. {@code height} is the Y
 * extent of the run; fine voxels always use 1.
 */
public record Voxel(int x, int y, int z, int colorArgb, int size, int height) {

    public Voxel(int x, int y, int z, int colorArgb) {
        this(x, y, z, colorArgb, 1, 1);
    }
}
