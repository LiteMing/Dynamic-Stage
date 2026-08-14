package vibe.liteming.dynamicstage.client.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
        assertEquals(0x12ABEF, display.color(0));
    }

    @Test
    void stageColorRemainsTheDefault() throws IOException {
        Path path = temporaryDirectory.resolve("client.json");
        Files.writeString(path, "{}");

        StageClientConfig.BoundaryDisplay display = StageClientConfig.read(path);

        assertNull(display.colorOverride());
        assertEquals(0x334455, display.color(0x334455));
    }

    @Test
    void rejectsOutOfRangeValues() throws IOException {
        Path path = temporaryDirectory.resolve("client.json");
        Files.writeString(path, "{\"boundary_opacity\": 2}");

        assertThrows(IllegalArgumentException.class, () -> StageClientConfig.read(path));
    }
}
