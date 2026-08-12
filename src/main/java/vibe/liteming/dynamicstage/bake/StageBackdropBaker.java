package vibe.liteming.dynamicstage.bake;

import net.minecraft.core.BlockPos;
import vibe.liteming.dynamicstage.backdrop.BackdropBlobIO;
import vibe.liteming.dynamicstage.backdrop.BackdropColumn;
import vibe.liteming.dynamicstage.backdrop.BackdropManifest;
import vibe.liteming.dynamicstage.backdrop.BackdropProducts;
import vibe.liteming.dynamicstage.bake.lod.DHFileReader;
import vibe.liteming.dynamicstage.bake.lod.Voxel;
import vibe.liteming.dynamicstage.bake.lod.VoxyDirectReader;
import vibe.liteming.dynamicstage.stage.StageSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Converts external LOD data into a portable, content-addressed backdrop blob. */
public final class StageBackdropBaker {

    private static final int BAKE_VERSION = 1;

    private StageBackdropBaker() {
    }

    public static String requestKey(Path dataFile, BlockPos anchor, String source, String sourceDimension) {
        String value = "bake-v" + BAKE_VERSION + '|' + source + '|' + sourceDimension + '|'
                + dataFile.toAbsolutePath().normalize() + '|'
                + anchor.getX() + '|' + anchor.getY() + '|' + anchor.getZ() + '|'
                + sourceStamp(dataFile);
        return BackdropProducts.sha256Hex(value.getBytes(StandardCharsets.UTF_8)).substring(0, 24);
    }

    public static BackdropProducts.Product bake(Path worldRoot, String stageId, String sourceDimension,
                                                 Path dataFile, BlockPos anchor, String source,
                                                 String requestKey) throws IOException {
        List<Voxel> voxels = StageSession.SOURCE_DH.equals(source)
                ? DHFileReader.read(dataFile, anchor)
                : VoxyDirectReader.read(dataFile, anchor);
        if (voxels.isEmpty()) {
            throw new IOException("LOD source returned no voxels near the requested anchor");
        }

        return encode(worldRoot, stageId, sourceDimension, anchor, source, requestKey, voxels);
    }

    static BackdropProducts.Product encode(Path worldRoot, String stageId, String sourceDimension,
                                            BlockPos anchor, String source, String requestKey,
                                            List<Voxel> voxels) throws IOException {
        if (voxels.isEmpty()) {
            throw new IOException("Cannot encode an empty LOD backdrop");
        }

        voxels.sort(Comparator.comparingInt(Voxel::x)
                .thenComparingInt(Voxel::z)
                .thenComparingInt(Voxel::size)
                .thenComparingInt(Voxel::colorArgb)
                .thenComparingInt(Voxel::y));

        List<Integer> palette = new ArrayList<>();
        Map<Integer, Integer> paletteIndices = new HashMap<>();
        List<BackdropColumn> columns = new ArrayList<>();
        Voxel run = null;
        int runEnd = 0;
        for (Voxel voxel : voxels) {
            validateRelativeCoordinate(voxel.x() - anchor.getX(), "x");
            validateRelativeCoordinate(voxel.z() - anchor.getZ(), "z");
            if (run != null && sameColumn(run, voxel) && voxel.y() <= runEnd) {
                runEnd = Math.max(runEnd, voxel.y() + voxel.height());
                continue;
            }
            if (run != null) {
                appendColumn(columns, palette, paletteIndices, run, runEnd, anchor);
            }
            run = voxel;
            runEnd = voxel.y() + voxel.height();
        }
        if (run != null) {
            appendColumn(columns, palette, paletteIndices, run, runEnd, anchor);
        }

        int[] paletteArray = palette.stream().mapToInt(Integer::intValue).toArray();
        String provider = StageSession.SOURCE_DH.equals(source) ? "distant_horizons" : "voxy_file";
        BackdropManifest manifest = new BackdropManifest(
                BackdropManifest.FORMAT_VERSION, stageId, sourceDimension,
                anchor.getX(), anchor.getY(), anchor.getZ(),
                provider, 1, requestKey, System.currentTimeMillis(),
                VoxyDirectReader.L2_RADIUS, null, List.of(), 0,
                paletteArray, 0xFF808080, new int[]{0, 1, 2}, false,
                2.0D, 1.0D, 1.0D, true, false, true);
        byte[] blob = BackdropBlobIO.write(manifest, columns);
        return BackdropProducts.store(worldRoot, stageId, blob, requestKey);
    }

    private static boolean sameColumn(Voxel first, Voxel next) {
        return first.x() == next.x()
                && first.z() == next.z()
                && first.size() == next.size()
                && first.colorArgb() == next.colorArgb();
    }

    private static void appendColumn(List<BackdropColumn> columns, List<Integer> palette,
                                     Map<Integer, Integer> paletteIndices, Voxel start, int end,
                                     BlockPos anchor) throws IOException {
        if (start.y() < Short.MIN_VALUE || end > Short.MAX_VALUE) {
            throw new IOException("Backdrop Y coordinate is outside the v1 format range");
        }
        int paletteIndex = paletteIndices.computeIfAbsent(start.colorArgb(), color -> {
            palette.add(color);
            return palette.size() - 1;
        });
        int lodLevel = Integer.numberOfTrailingZeros(Integer.highestOneBit(Math.max(1, start.size())));
        columns.add(new BackdropColumn(start.x() - anchor.getX(), start.z() - anchor.getZ(),
                start.y(), end, paletteIndex, lodLevel));
    }

    private static void validateRelativeCoordinate(int coordinate, String axis) throws IOException {
        if (coordinate < Short.MIN_VALUE || coordinate > Short.MAX_VALUE) {
            throw new IOException("Backdrop " + axis + " coordinate is outside the v1 format range");
        }
    }

    private static long sourceStamp(Path dataFile) {
        try {
            if (Files.isRegularFile(dataFile)) {
                long stamp = stampFile(dataFile, 1L);
                Path wal = dataFile.resolveSibling(dataFile.getFileName() + "-wal");
                return Files.isRegularFile(wal) ? stampFile(wal, stamp) : stamp;
            }
            long stamp = Files.getLastModifiedTime(dataFile).toMillis();
            try (var files = Files.list(dataFile)) {
                for (Path file : files.filter(Files::isRegularFile)
                        .filter(StageBackdropBaker::isVoxyDataFile)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList()) {
                    stamp = stampFile(file, stamp);
                }
            }
            return stamp;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static long stampFile(Path file, long seed) throws IOException {
        long stamp = 31L * seed + file.getFileName().toString().hashCode();
        stamp = 31L * stamp + Files.getLastModifiedTime(file).toMillis();
        return 31L * stamp + Files.size(file);
    }

    private static boolean isVoxyDataFile(Path file) {
        String name = file.getFileName().toString();
        return name.equals("CURRENT") || name.startsWith("MANIFEST-") || name.startsWith("OPTIONS-")
                || name.endsWith(".sst") || name.endsWith(".log");
    }
}
