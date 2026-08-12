package vibe.liteming.dynamicstage.bake.voxy;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Plain-main probe for the direct Voxy read path (no JUnit discovery involved).
 * Usage: gradlew voxyProbe -PvoxyProbe=<storageDir>
 */
public final class VoxyDirectProbe {

    private VoxyDirectProbe() {
    }

    public static void main(String[] args) {
        String dir = args.length > 0 ? args[0] : System.getProperty("voxy.probe");
        if (dir == null) {
            System.out.println("usage: -PvoxyProbe=<storageDir>");
            return;
        }
        int anchorX = 11104;
        int anchorZ = 15584;
        VoxyRocksDB db = VoxyRocksDB.open(Path.of(dir));
        if (db == null || !db.isOpen()) {
            System.out.println("OPEN FAILED: " + dir);
            return;
        }
        int[] radii = {96, 192, 384};
        long total = 0;
        for (int level = 0; level <= 2; level++) {
            int size = VoxySectionKey.sectionSize(level);
            int maxDist = radii[level];
            AtomicLong sections = new AtomicLong();
            AtomicLong voxels = new AtomicLong();
            db.iterateSections(level, key -> {
                int sx = VoxySectionKey.xOf(key);
                int sz = VoxySectionKey.zOf(key);
                double dx = (sx + 0.5D) * size - anchorX;
                double dz = (sz + 0.5D) * size - anchorZ;
                if (Math.sqrt(dx * dx + dz * dz) > maxDist) {
                    return;
                }
                byte[] compressed = db.getSection(key);
                if (compressed == null) {
                    return;
                }
                sections.incrementAndGet();
                VoxySectionParser.parseAll(compressed, (bx, by, bz, bid) -> voxels.incrementAndGet());
            });
            total += voxels.get();
            System.out.println("level=" + level + " (size " + size + ", r<=" + maxDist
                    + ") sections=" + sections + " voxels=" + voxels);
        }
        System.out.println("total voxels=" + total);
        db.close();
    }
}
