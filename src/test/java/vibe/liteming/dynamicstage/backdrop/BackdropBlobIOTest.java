package vibe.liteming.dynamicstage.backdrop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BackdropBlobIOTest {

    @Test
    void roundTripPreservesColumnsAndManifest() {
        BackdropManifest manifest = new BackdropManifest(
                1, "dynamicstage:demo", "minecraft:overworld",
                100, 64, -200, "voxy_file", 1, "abc", 12345L,
                96, null, List.of(), 0,
                new int[]{0xFF336699, 0xFF77AA33}, 0xFF808080, new int[]{0, 1, 2}, false,
                2.0, 0.8, 0.9, true, false, true);

        List<BackdropColumn> columns = List.of(
                new BackdropColumn(0, 0, 62, 64, 0, 0),
                new BackdropColumn(1, 0, 63, 64, 1, 0),
                new BackdropColumn(2, 4, 60, 64, 0, 1));

        byte[] blob = BackdropBlobIO.write(manifest, columns);
        BackdropBlobIO.Blob parsed = BackdropBlobIO.read(blob);

        assertEquals("dynamicstage:demo", parsed.manifest().getStageId());
        assertEquals(100, parsed.manifest().getAnchorX());
        assertEquals(columns.size(), parsed.columns().size());
        for (int i = 0; i < columns.size(); i++) {
            assertEquals(columns.get(i), parsed.columns().get(i));
        }
    }
}
