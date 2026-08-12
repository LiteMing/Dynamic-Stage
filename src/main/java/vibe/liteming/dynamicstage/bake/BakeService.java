package vibe.liteming.dynamicstage.bake;

import vibe.liteming.dynamicstage.backdrop.BackdropBlobIO;
import vibe.liteming.dynamicstage.backdrop.BackdropColumn;
import vibe.liteming.dynamicstage.backdrop.BackdropManifest;
import vibe.liteming.dynamicstage.bake.anvil.MapColorMapper;
import vibe.liteming.dynamicstage.bake.voxy.VoxyProvider;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Minimal on-demand bake: reads a source (Voxy DB), produces a {@code .sdb}
 * blob and writes it to the client cache dir where the renderer picks it up.
 * Runs on the server thread of the caller.
 */
public final class BakeService {

    public static final int DEFAULT_RADIUS = 96;

    private BakeService() {
    }

    /**
     * Bakes a disc region around the given anchor from a Voxy database.
     *
     * @param stageId     stage id used for the cache file name
     * @param storageDir  Voxy RocksDB storage directory
     * @param anchorX,anchorZ  source-world bake centre
     * @return the written .sdb path, or null on failure
     */
    @Nullable
    public static Path bakeVoxy(String stageId, Path storageDir, int anchorX, int anchorZ) {
        VoxyProvider provider = new VoxyProvider(storageDir, new MapColorMapper());
        RegionSpec spec = RegionSpec.disc(anchorX, 64, anchorZ, DEFAULT_RADIUS);

        List<BackdropColumn> columns = new ArrayList<>();
        List<Integer> palette = new ArrayList<>();
        java.util.Map<Integer, Integer> colorIndex = new java.util.HashMap<>();
        provider.readRegion(spec).forEach(col -> {
            int color = col.colorArgb();
            int idx = colorIndex.computeIfAbsent(color, k -> {
                palette.add(color);
                return palette.size() - 1;
            });
            columns.add(new BackdropColumn(col.x() - anchorX, col.z() - anchorZ,
                    col.yStart(), col.yEnd(), idx, col.lodLevel()));
        });
        if (columns.isEmpty()) {
            return null;
        }

        int[] paletteArr = new int[palette.size()];
        for (int j = 0; j < palette.size(); j++) {
            paletteArr[j] = palette.get(j);
        }

        BackdropManifest manifest = new BackdropManifest(
                BackdropManifest.FORMAT_VERSION, stageId, "minecraft:overworld",
                anchorX, 64, anchorZ,
                VoxyProvider.ID, VoxyProvider.FORMAT_VERSION, "local",
                System.currentTimeMillis(),
                DEFAULT_RADIUS, null, List.of(), 0,
                paletteArr, 0xFF808080, new int[]{0, 1, 2}, false,
                2.0D, 0.8D, 0.9D, true, false, true);

        byte[] blob = BackdropBlobIO.write(manifest, columns);
        Path outDir = Path.of(".").toAbsolutePath().resolve("dynamicstage").resolve("backdrops")
                .resolve(sanitise(stageId));
        try {
            Files.createDirectories(outDir);
            Path target = outDir.resolve(hex(blob).substring(0, 16) + ".sdb");
            Files.write(target, blob);
            return target;
        } catch (IOException e) {
            return null;
        }
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    private static String sanitise(String id) {
        return id.replace(':', '_').replace('/', '_');
    }
}
