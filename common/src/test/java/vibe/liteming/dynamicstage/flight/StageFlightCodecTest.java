package vibe.liteming.dynamicstage.flight;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StageFlightCodecTest {

    @Test
    void selectsAndCanonicalizesOneBasedSceneSlot() throws Exception {
        byte[] export = ("[" + scene(1000, "outside", 0, 2) + ","
                + scene(2500, "outside", 0, 3)
                .replace("\"smooth_start\":false", "\"smooth_start\":0")
                .replace("\"d_timing\":false", "\"d_timing\":0")
                + "]").getBytes(StandardCharsets.UTF_8);

        StageFlightCodec.Scene selected = StageFlightCodec.select(export, 2);
        StageFlightCodec.Scene reread = StageFlightCodec.readSingle(selected.json());

        assertEquals(2500L, reread.durationMillis());
        assertEquals(3, reread.pointCount());
    }

    @Test
    void acceptsLodOnlyModesLoopsAndIgnoresSmoothStart() throws Exception {
        byte[] export = ("[" + scene(1000, "default", -1, 2)
                .replace("\"smooth_start\":false", "\"smooth_start\":true") + "]")
                .getBytes(StandardCharsets.UTF_8);

        StageFlightCodec.Scene selected = StageFlightCodec.select(export, 1);
        com.google.gson.JsonObject canonical = com.google.gson.JsonParser.parseString(
                new String(selected.json(), StandardCharsets.UTF_8)).getAsJsonObject();

        assertEquals(-1, canonical.get("loop").getAsInt());
        assertEquals("default", canonical.get("mode").getAsString());
        assertFalse(canonical.get("smooth_start").getAsBoolean());
    }

    @Test
    void rejectsTargetsAndInvalidLoops() {
        assertThrows(IOException.class, () -> StageFlightCodec.select(
                ("[" + scene(1000, "outside", -2, 2) + "]").getBytes(StandardCharsets.UTF_8), 1));
        assertThrows(IOException.class, () -> StageFlightCodec.select(
                ("[" + scene(1000, "outside", 0, 1) + "]").getBytes(StandardCharsets.UTF_8), 1));
        assertThrows(IOException.class, () -> StageFlightCodec.select(
                ("[{\"duration\":1000,\"loop\":0,\"mode\":\"outside\",\"inter\":\"linear\","
                        + "\"pos_target\":{},\"points\":[" + point(0) + "," + point(1) + "]}]")
                        .getBytes(StandardCharsets.UTF_8), 1));
        assertThrows(IOException.class, () -> StageFlightCodec.select(
                ("[" + scene(1000, "outside", 0, 2).replace("\"d_timing\":false", "\"d_timing\":true")
                        .replace(point(1), point(0)) + "]").getBytes(StandardCharsets.UTF_8), 1));
    }

    @Test
    void rejectsBadSlotDeepJsonAndNonFiniteNumbers() {
        byte[] valid = ("[" + scene(1000, "outside", 0, 2) + "]").getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> StageFlightCodec.select(valid, 2));

        String deep = "[".repeat(40) + "0" + "]".repeat(40);
        assertThrows(IOException.class, () -> StageFlightCodec.select(deep.getBytes(StandardCharsets.UTF_8), 1));

        String infinite = "[" + scene(1000, "outside", 0, 2).replace("\"x\":0", "\"x\":1e999") + "]";
        assertThrows(IOException.class,
                () -> StageFlightCodec.select(infinite.getBytes(StandardCharsets.UTF_8), 1));
    }

    static String scene(long duration, String mode, int loop, int points) {
        StringBuilder json = new StringBuilder("{\"duration\":").append(duration)
                .append(",\"loop\":").append(loop)
                .append(",\"mode\":\"").append(mode)
                .append("\",\"inter\":\"linear\",\"smooth_start\":false,\"pitch_mode\":0,\"d_timing\":false,\"points\":[");
        for (int i = 0; i < points; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append(point(i));
        }
        return json.append("]}").toString();
    }

    private static String point(int x) {
        return "{\"x\":" + x + ",\"y\":64,\"z\":0,\"rotationYaw\":0,"
                + "\"rotationPitch\":0,\"roll\":0,\"zoom\":70}";
    }
}
