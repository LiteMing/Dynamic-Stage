package vibe.liteming.dynamicstage.client.flight;

import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CMDCamJsonNbtTest {

    @Test
    void convertsCMDCamSceneNumbersAndCompoundPointLists() {
        CompoundTag scene = CMDCamJsonNbt.convert(JsonParser.parseString("""
                {
                  "duration": 2500,
                  "smooth_start": 0,
                  "d_timing": false,
                  "points": [
                    {"x": 1.5, "y": 64, "z": 2},
                    {"x": 3.5, "y": 65, "z": 4}
                  ]
                }
                """).getAsJsonObject());

        assertEquals(2500L, scene.getLong("duration"));
        assertFalse(scene.getBoolean("smooth_start"));
        assertFalse(scene.getBoolean("d_timing"));
        assertEquals(2, scene.getList("points", Tag.TAG_COMPOUND).size());
        assertEquals(1.5D, scene.getList("points", Tag.TAG_COMPOUND)
                .getCompound(0).getDouble("x"));
    }

    @Test
    void rejectsMixedJsonArraysThatNbtCannotRepresent() {
        assertThrows(IllegalArgumentException.class, () -> CMDCamJsonNbt.convert(
                JsonParser.parseString("{\"mixed\":[1,{}]}").getAsJsonObject()));
    }
}
