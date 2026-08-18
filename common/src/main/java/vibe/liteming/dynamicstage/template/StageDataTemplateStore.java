package vibe.liteming.dynamicstage.template;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;

import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
                StageTemplate template = parse(JsonParser.parseReader(reader).getAsJsonObject());
                StageTemplate duplicate = templates.putIfAbsent(template.id(), template);
                if (duplicate != null) {
                    throw new IOException("duplicate data-pack stage template id '" + template.id() + "'");
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new IOException("invalid data-pack stage template " + entry.getKey() + ": "
                        + e.getMessage(), e);
            }
        }
        return Map.copyOf(templates);
    }

    static StageTemplate parse(JsonObject json) {
        String id = requiredString(json, "id");
        ResourceLocation lodPack = new ResourceLocation(string(json, "lod_pack", "dynamicstage:none"));
        BlockPos anchor = blockPos(json.get("lod_anchor"), BlockPos.ZERO);
        StageBoundary boundary = boundary(json.getAsJsonObject("boundary"));
        StageClientScene scene = scene(json.getAsJsonObject("scene"));
        int capacity = integer(json, "capacity", 1);
        StageTemplate.InstanceMode instanceMode = enumValue(StageTemplate.InstanceMode.class,
                string(json, "instance_mode", "parallel"));
        StageTemplate.LifecyclePolicy lifecycle = switch (string(json, "lifecycle", "release")
                .toLowerCase(Locale.ROOT)) {
            case "release", "release_when_empty" -> StageTemplate.LifecyclePolicy.RELEASE_WHEN_EMPTY;
            case "retain" -> StageTemplate.LifecyclePolicy.RETAIN;
            default -> throw new IllegalArgumentException("invalid lifecycle");
        };
        BlockPos entryOffset = blockPos(json.get("entry_offset"), BlockPos.ZERO);
        List<StageStructurePlacement> structures = structures(json.getAsJsonArray("structures"));
        return new StageTemplate(id, lodPack, anchor, boundary, scene, capacity, instanceMode, lifecycle,
                new byte[0], new CompoundTag(), "", entryOffset, structures);
    }

    private static StageBoundary boundary(JsonObject json) {
        if (json == null) {
            return StageBoundary.defaults();
        }
        int color = json.has("color") ? parseColor(json.get("color")) : StageBoundary.UNSET_COLOR;
        return new StageBoundary(integer(json, "width", StageBoundary.DEFAULT_WIDTH),
                integer(json, "depth", StageBoundary.DEFAULT_DEPTH),
                integer(json, "height", StageBoundary.DEFAULT_HEIGHT), color);
    }

    private static StageClientScene scene(JsonObject json) {
        StageClientScene defaults = StageClientScene.defaults(0L, 0L);
        if (json == null) {
            return defaults;
        }
        StageClientScene.Transition transition = enumValue(StageClientScene.Transition.class,
                string(json, "lod_transition", defaults.lodTransition().name()));
        int transitionTicks = integer(json, "lod_transition_ticks",
                transition == StageClientScene.Transition.INSTANT ? 0 : defaults.lodTransitionTicks());
        StageClientScene.TimeMode timeMode = enumValue(StageClientScene.TimeMode.class,
                string(json, "time_mode", defaults.timeMode().name()));
        long cycleTicks = longValue(json, "time_cycle_ticks",
                timeMode == StageClientScene.TimeMode.CYCLE ? 24_000L : 0L);
        return new StageClientScene(bool(json, "follow_player", defaults.followPlayer()),
                decimal(json, "lod_movement_scale", defaults.lodMovementScale()),
                decimal(json, "dh_near_fade_scale", defaults.dhNearFadeScale()),
                bool(json, "voxy_near_culling", defaults.voxyNearCulling()),
                bool(json, "lod_visible", defaults.lodVisible()),
                decimal(json, "lod_blur_radius", defaults.lodBlurRadius()), transition, transitionTicks, 0L,
                timeMode, longValue(json, "day_time", defaults.timeBaseDayTime()), 0L, cycleTicks,
                enumValue(StageClientScene.SkyMode.class, string(json, "sky", defaults.skyMode().name())));
    }

    private static List<StageStructurePlacement> structures(JsonArray json) {
        if (json == null) {
            return List.of();
        }
        java.util.ArrayList<StageStructurePlacement> placements = new java.util.ArrayList<>();
        for (JsonElement element : json) {
            JsonObject placement = element.getAsJsonObject();
            placements.add(new StageStructurePlacement(new ResourceLocation(requiredString(placement, "id")),
                    blockPos(placement.get("offset"), BlockPos.ZERO)));
        }
        return List.copyOf(placements);
    }

    private static BlockPos blockPos(JsonElement element, BlockPos fallback) {
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != 3) {
            throw new IllegalArgumentException("block position must contain three integers");
        }
        return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    private static int parseColor(JsonElement value) {
        if (value.getAsJsonPrimitive().isNumber()) {
            return value.getAsInt();
        }
        String text = value.getAsString().replace("#", "");
        return Integer.parseInt(text, 16);
    }

    private static String requiredString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return json.get(key).getAsString();
    }

    private static String string(JsonObject json, String key, String fallback) {
        return json.has(key) ? json.get(key).getAsString() : fallback;
    }

    private static int integer(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }

    private static long longValue(JsonObject json, String key, long fallback) {
        return json.has(key) ? json.get(key).getAsLong() : fallback;
    }

    private static float decimal(JsonObject json, String key, float fallback) {
        return json.has(key) ? json.get(key).getAsFloat() : fallback;
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        return json.has(key) ? json.get(key).getAsBoolean() : fallback;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
    }
}
