package vibe.liteming.dynamicstage.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vibe.liteming.dynamicstage.stage.StageBoundary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StageClientConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void readsBoundaryDisplayOverrides() throws IOException {
        Path path = temporaryDirectory.resolve("client.json");
        Files.writeString(path, """
                {"boundary_visible_distance": 24.5, "boundary_opacity": 0.4, "boundary_color": "#12ABEF"}
                """);

        StageClientConfig.BoundaryDisplay display = StageClientConfig.read(path);

        assertEquals(24.5D, display.visibleDistance());
        assertEquals(0.4F, display.opacity());
        assertEquals(0x334455, display.color(0x334455));
        assertEquals(0x12ABEF, display.color(StageBoundary.UNSET_COLOR));
    }

    @Test
    void stageColorRemainsTheDefault() throws IOException {
        Path path = temporaryDirectory.resolve("client.json");
        Files.writeString(path, "{}");

        StageClientConfig.BoundaryDisplay display = StageClientConfig.read(path);

        assertNull(display.fallbackColor());
        assertEquals(0x334455, display.color(0x334455));
        assertEquals(StageBoundary.DEFAULT_COLOR, display.color(StageBoundary.UNSET_COLOR));
    }

    @Test
    void rejectsOutOfRangeValues() throws IOException {
        Path path = temporaryDirectory.resolve("client.json");
        Files.writeString(path, "{\"boundary_opacity\": 2}");

        assertThrows(IllegalArgumentException.class, () -> StageClientConfig.read(path));
    }

    @Test
    void writesBoundaryDisplayAtomically() throws IOException {
        Path path = temporaryDirectory.resolve("client.json");
        StageClientConfig.BoundaryDisplay expected =
                StageClientConfig.createBoundaryDisplay(32.0D, 0.65F, "A1B2C3");

        StageClientConfig.write(path, expected);

        assertEquals(expected, StageClientConfig.read(path));
    }
}
