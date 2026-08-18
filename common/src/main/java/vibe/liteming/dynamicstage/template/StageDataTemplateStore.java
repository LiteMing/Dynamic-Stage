package vibe.liteming.dynamicstage.template;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;

import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;

/** Data-pack supplied templates used as the immutable base layer for modpack stages. */
final class StageDataTemplateStore {
    private static final String ROOT = "dynamicstage/stages";

    private StageDataTemplateStore() {
    }

    static Map<String, StageTemplate> load(MinecraftServer server) throws IOException {
        Map<String, StageTemplate> templates = new LinkedHashMap<>();
        Map<ResourceLocation, Resource> resources = server.getResourceManager().listResources(ROOT,
                id -> id.getPath().endsWith(".json"));
        for (Map.Entry<ResourceLocation, Resource> entry : resources.entrySet()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                JsonElement root = JsonParser.parseReader(reader);
                if (!root.isJsonObject()) {
                    throw new IllegalArgumentException("template root must be a JSON object");
                }
                StageTemplate template = parse(root.getAsJsonObject());
                StageTemplate duplicate = templates.putIfAbsent(template.id(), template);
                if (duplicate != null) {
                    throw new IOException("duplicate data-pack stage template id '" + template.id() + "'");
                }
            } catch (IOException | RuntimeException e) {
                throw new IOException("invalid data-pack stage template " + entry.getKey() + ": "
                        + e.getMessage(), e);
            }
        }
        return Map.copyOf(templates);
    }

    static StageTemplate parse(com.google.gson.JsonObject json) throws IOException {
        if (json.has("arena")) {
            throw new IOException("data-pack templates must use structures instead of local arena files");
        }
        return StageTemplateJsonCodec.parse(json, new CompoundTag());
    }
}
