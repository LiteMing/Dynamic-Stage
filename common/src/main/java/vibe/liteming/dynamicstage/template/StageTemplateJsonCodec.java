package vibe.liteming.dynamicstage.template;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.flight.StageFlightAssets;
import vibe.liteming.dynamicstage.flight.StageFlightCodec;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared human-editable JSON representation for data-pack and portable templates. */
final class StageTemplateJsonCodec {
    private static final int FORMAT_VERSION = 1;

    private StageTemplateJsonCodec() {
    }

    static StageTemplate parse(JsonObject json, CompoundTag arenaSnapshot) throws IOException {
        return parse(json, arenaSnapshot, StageFlightAssets::readLibraryJson);
    }

    static StageTemplate parse(JsonObject json, CompoundTag arenaSnapshot,
                               FlightResolver flightResolver) throws IOException {
        if (json.has("format") && json.get("format").getAsInt() != FORMAT_VERSION) {
            throw new IOException("unsupported stage template JSON format: " + json.get("format"));
        }
        String id = requiredString(json, "id");
        if (!id.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IOException("template id must use 1-64 letters, digits, '_' or '-'");
        }
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
            default -> throw new IOException("invalid lifecycle");
        };
        StageTemplate.CleanupPolicy cleanup = switch (string(json, "cleanup", "full")
                .toLowerCase(Locale.ROOT)) {
            case "full" -> StageTemplate.CleanupPolicy.FULL;
            case "overlay" -> StageTemplate.CleanupPolicy.OVERLAY;
            default -> throw new IOException("invalid cleanup policy");
        };
        StageTemplate.InteractionPolicy interaction = switch (string(json, "interaction", "adventure")
                .toLowerCase(Locale.ROOT)) {
            case "locked" -> StageTemplate.InteractionPolicy.LOCKED;
            case "adventure" -> StageTemplate.InteractionPolicy.ADVENTURE;
            default -> throw new IOException("invalid interaction policy");
        };
        Flight flight = flight(json.get("flight"), flightResolver);
        BlockPos entryOffset = blockPos(json.get("entry_offset"), BlockPos.ZERO);
        List<StageStructurePlacement> structures = structures(json.getAsJsonArray("structures"));
        return new StageTemplate(id, lodPack, anchor, boundary, scene, capacity, instanceMode, lifecycle,
                cleanup, interaction, flight.json(), arenaSnapshot, flight.name(), entryOffset, structures);
    }

    static JsonObject write(StageTemplate template, String arenaFile) {
        JsonObject json = new JsonObject();
        json.addProperty("format", FORMAT_VERSION);
        json.addProperty("id", template.id());
        json.addProperty("lod_pack", template.lodPackId().toString());
        json.add("lod_anchor", blockPos(template.lodAnchor()));

        JsonObject boundary = new JsonObject();
        boundary.addProperty("width", template.boundary().width());
        boundary.addProperty("depth", template.boundary().depth());
        boundary.addProperty("height", template.boundary().height());
        if (template.boundary().color() != StageBoundary.UNSET_COLOR) {
            boundary.addProperty("color", String.format(Locale.ROOT, "%06X", template.boundary().color()));
        }
        json.add("boundary", boundary);

        StageClientScene scene = template.clientScene();
        JsonObject sceneJson = new JsonObject();
        sceneJson.addProperty("follow_player", scene.followPlayer());
        sceneJson.addProperty("lod_movement_scale", scene.lodMovementScale());
        sceneJson.addProperty("dh_near_fade_scale", scene.dhNearFadeScale());
        sceneJson.addProperty("voxy_near_plane", scene.voxyNearPlane());
        sceneJson.addProperty("voxy_near_culling", scene.voxyNearCulling());
        sceneJson.addProperty("lod_visible", scene.lodVisible());
        sceneJson.addProperty("lod_blur_radius", scene.lodBlurRadius());
        sceneJson.addProperty("lod_transition", scene.lodTransition().name().toLowerCase(Locale.ROOT));
        sceneJson.addProperty("lod_transition_ticks", scene.lodTransitionTicks());
        sceneJson.addProperty("lod_transition_start_game_time", scene.lodTransitionStartGameTime());
        sceneJson.addProperty("time_mode", scene.timeMode().name().toLowerCase(Locale.ROOT));
        sceneJson.addProperty("day_time", scene.timeBaseDayTime());
        sceneJson.addProperty("time_base_game_time", scene.timeBaseGameTime());
        sceneJson.addProperty("time_cycle_ticks", scene.timeCycleTicks());
        sceneJson.addProperty("sky", scene.skyMode().name().toLowerCase(Locale.ROOT));
        json.add("scene", sceneJson);

        json.addProperty("capacity", template.capacity());
        json.addProperty("instance_mode", template.instanceMode().name().toLowerCase(Locale.ROOT));
        json.addProperty("lifecycle", template.lifecyclePolicy() == StageTemplate.LifecyclePolicy.RETAIN
                ? "retain" : "release");
        json.addProperty("cleanup", template.cleanupPolicy().name().toLowerCase(Locale.ROOT));
        json.addProperty("interaction", template.interactionPolicy().name().toLowerCase(Locale.ROOT));
        if (template.hasFlight()) {
            if (!template.flightName().isEmpty()) {
                json.addProperty("flight", template.flightName());
            } else {
                json.add("flight", JsonParser.parseString(
                        new String(template.flightJson(), StandardCharsets.UTF_8)));
            }
        }
        if (template.hasArenaSnapshot() && arenaFile != null && !arenaFile.isBlank()) {
            json.addProperty("arena", arenaFile);
        }
        if (!template.entryOffset().equals(BlockPos.ZERO)) {
            json.add("entry_offset", blockPos(template.entryOffset()));
        }
        if (!template.structures().isEmpty()) {
            JsonArray structures = new JsonArray();
            template.structures().forEach(placement -> {
                JsonObject structure = new JsonObject();
                structure.addProperty("id", placement.structureId().toString());
                structure.add("offset", blockPos(placement.offset()));
                structures.add(structure);
            });
            json.add("structures", structures);
        }
        return json;
    }

    private static Flight flight(JsonElement element, FlightResolver flightResolver) throws IOException {
        if (element == null || element.isJsonNull()) {
            return new Flight(new byte[0], "");
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            String name = element.getAsString();
            if (!StageTemplate.validFlightName(name)) {
                throw new IOException("invalid template flight name");
            }
            return new Flight(flightResolver.load(name), name);
        }
        if (!element.isJsonObject()) {
            throw new IOException("template flight must be a library name or JSON object");
        }
        try {
            byte[] json = StageFlightCodec.readSingle(
                    element.toString().getBytes(StandardCharsets.UTF_8)).json();
            return new Flight(json, "");
        } catch (RuntimeException e) {
            throw new IOException("invalid template flight", e);
        }
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
                decimal(json, "voxy_near_plane", defaults.voxyNearPlane()),
                bool(json, "voxy_near_culling", defaults.voxyNearCulling()),
                bool(json, "lod_visible", defaults.lodVisible()),
                decimal(json, "lod_blur_radius", defaults.lodBlurRadius()), transition, transitionTicks,
                longValue(json, "lod_transition_start_game_time", 0L), timeMode,
                longValue(json, "day_time", defaults.timeBaseDayTime()),
                longValue(json, "time_base_game_time", 0L), cycleTicks,
                enumValue(StageClientScene.SkyMode.class, string(json, "sky", defaults.skyMode().name())));
    }

    private static List<StageStructurePlacement> structures(JsonArray json) {
        if (json == null) {
            return List.of();
        }
        ArrayList<StageStructurePlacement> placements = new ArrayList<>();
        for (JsonElement element : json) {
            JsonObject placement = element.getAsJsonObject();
            placements.add(new StageStructurePlacement(new ResourceLocation(requiredString(placement, "id")),
                    blockPos(placement.get("offset"), BlockPos.ZERO)));
        }
        return List.copyOf(placements);
    }

    private static JsonArray blockPos(BlockPos pos) {
        JsonArray array = new JsonArray();
        array.add(pos.getX());
        array.add(pos.getY());
        array.add(pos.getZ());
        return array;
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

    private record Flight(byte[] json, String name) {
    }

    @FunctionalInterface
    interface FlightResolver {
        byte[] load(String name) throws IOException;
    }
}
