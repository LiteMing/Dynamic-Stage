package vibe.liteming.dynamicstage.client.flight;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.Map;

/** Mirrors CMDCam's scene JSON-to-NBT representation without requiring a specific CMDCam utility version. */
final class CMDCamJsonNbt {

    private CMDCamJsonNbt() {
    }

    static CompoundTag convert(JsonObject json) {
        CompoundTag nbt = new CompoundTag();
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            nbt.put(entry.getKey(), convert(entry.getValue()));
        }
        return nbt;
    }

    private static Tag convert(JsonElement json) {
        if (json.isJsonObject()) {
            return convert(json.getAsJsonObject());
        }
        if (json.isJsonArray()) {
            ListTag list = new ListTag();
            for (JsonElement element : json.getAsJsonArray()) {
                if (!list.addTag(list.size(), convert(element))) {
                    throw new IllegalArgumentException("CMDCam JSON array contains mixed NBT value types");
                }
            }
            return list;
        }
        if (!json.isJsonPrimitive()) {
            return StringTag.valueOf(json.toString());
        }
        JsonPrimitive primitive = json.getAsJsonPrimitive();
        if (primitive.isBoolean()) {
            return ByteTag.valueOf(primitive.getAsBoolean());
        }
        if (primitive.isNumber()) {
            String value = primitive.getAsString();
            return value.contains(".") || value.contains("e") || value.contains("E")
                    ? DoubleTag.valueOf(primitive.getAsDouble())
                    : LongTag.valueOf(primitive.getAsLong());
        }
        return StringTag.valueOf(primitive.getAsString());
    }
}
