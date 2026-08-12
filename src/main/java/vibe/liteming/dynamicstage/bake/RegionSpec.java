package vibe.liteming.dynamicstage.bake;

import java.util.List;

/**
 * Bake region specification: either a circular disc around an anchor or a spline
 * corridor. {@link #isCorridor()} distinguishes the two.
 */
public record RegionSpec(int anchorX, int anchorY, int anchorZ, int radius,
                         String corridorType, List<int[]> corridorPoints, int corridorRadius) {

    public boolean isCorridor() {
        return corridorPoints != null && !corridorPoints.isEmpty();
    }

    public static RegionSpec disc(int anchorX, int anchorY, int anchorZ, int radius) {
        return new RegionSpec(anchorX, anchorY, anchorZ, radius, null, List.of(), 0);
    }

    public static RegionSpec corridor(int anchorX, int anchorY, int anchorZ,
                                      String corridorType, List<int[]> points, int corridorRadius) {
        return new RegionSpec(anchorX, anchorY, anchorZ, 0, corridorType, points, corridorRadius);
    }

    /**
     * True when the given world position (relative to the world origin, not the
     * anchor) falls inside the bake range.
     */
    public boolean contains(int worldX, int worldY, int worldZ) {
        double dx = worldX - anchorX;
        double dz = worldZ - anchorZ;
        if (!isCorridor()) {
            return dx * dx + dz * dz <= (double) radius * radius;
        }

        // Corridor points are stage-local; the anchor is the projection origin
        // into the source world, so compare the world position relative to the
        // anchor against the local path.
        double minDistSq = Double.MAX_VALUE;
        for (int i = 0; i < corridorPoints.size() - 1; i++) {
            int[] a = corridorPoints.get(i);
            int[] b = corridorPoints.get(i + 1);
            minDistSq = Math.min(minDistSq, distToSegmentSq(dx, dz, a[0], a[2], b[0], b[2]));
        }
        double cr = corridorRadius;
        return minDistSq <= cr * cr;
    }

    private static double distToSegmentSq(double px, double pz, double ax, double az, double bx, double bz) {
        double vx = bx - ax;
        double vz = bz - az;
        double lenSq = vx * vx + vz * vz;
        if (lenSq <= 0.0D) {
            double ddx = px - ax;
            double ddz = pz - az;
            return ddx * ddx + ddz * ddz;
        }
        double t = ((px - ax) * vx + (pz - az) * vz) / lenSq;
        t = Math.max(0.0D, Math.min(1.0D, t));
        double cx = ax + t * vx;
        double cz = az + t * vz;
        double ddx = px - cx;
        double ddz = pz - cz;
        return ddx * ddx + ddz * ddz;
    }
}
