package vibe.liteming.dynamicstage.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import vibe.liteming.dynamicstage.flight.StageFlightAssets;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;
import vibe.liteming.dynamicstage.stage.StageSessionManager;
import vibe.liteming.dynamicstage.template.StageTemplate;
import vibe.liteming.dynamicstage.template.StageTemplateStore;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class DynamicStageCommands {

    private DynamicStageCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(buildRoot("dstage"));
        // Keep the long name as a compatibility alias for existing scripts.
        dispatcher.register(buildRoot("dynamicstage"));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildRoot(String name) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name);
        root.requires(source -> source.hasPermission(2));
        root.then(Commands.literal("start")
                        .then(Commands.argument("stage", StringArgumentType.string())
                                .then(Commands.argument("lod_pack", ResourceLocationArgument.id())
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(ctx -> start(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "stage"),
                                                                        ResourceLocationArgument.getId(ctx, "lod_pack"),
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"), 1))
                                                                .then(Commands.argument("capacity", IntegerArgumentType.integer(1,
                                                                                StageSession.MAX_CAPACITY))
                                                                        .executes(ctx -> start(ctx.getSource(),
                                                                                StringArgumentType.getString(ctx, "stage"),
                                                                                ResourceLocationArgument.getId(ctx, "lod_pack"),
                                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                                IntegerArgumentType.getInteger(ctx, "capacity"))))))))));
        root.then(Commands.literal("join")
                        .then(Commands.argument("instance", StringArgumentType.word())
                                .executes(ctx -> join(ctx.getSource(), StringArgumentType.getString(ctx, "instance")))));
        LiteralArgumentBuilder<CommandSourceStack> templates = Commands.literal("template");
        templates.then(Commands.literal("list").executes(ctx -> templateList(ctx.getSource())));
        templates.then(Commands.literal("reset").executes(ctx -> templateReset(ctx.getSource())));
        templates.then(Commands.literal("start")
                .then(Commands.argument("template", StringArgumentType.word())
                        .suggests(DynamicStageCommands::suggestTemplates)
                        .executes(ctx -> templateStart(ctx.getSource(),
                                StringArgumentType.getString(ctx, "template")))));
        templates.then(Commands.literal("delete")
                .then(Commands.argument("template", StringArgumentType.word())
                        .suggests(DynamicStageCommands::suggestTemplates)
                        .executes(ctx -> templateDelete(ctx.getSource(),
                                StringArgumentType.getString(ctx, "template")))));
        RequiredArgumentBuilder<CommandSourceStack, String> resetPolicy =
                Commands.argument("reset_policy", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                java.util.List.of("on_create", "manual"), builder));
        resetPolicy.executes(ctx -> templateSave(ctx.getSource(),
                StringArgumentType.getString(ctx, "template"),
                parseInstanceMode(StringArgumentType.getString(ctx, "instance_mode")),
                parseResetPolicy(StringArgumentType.getString(ctx, "reset_policy"))));
        RequiredArgumentBuilder<CommandSourceStack, String> instanceMode =
                Commands.argument("instance_mode", StringArgumentType.word())
                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                java.util.List.of("parallel", "shared"), builder));
        instanceMode.executes(ctx -> templateSave(ctx.getSource(),
                StringArgumentType.getString(ctx, "template"),
                parseInstanceMode(StringArgumentType.getString(ctx, "instance_mode")),
                StageTemplate.ResetPolicy.ON_CREATE));
        instanceMode.then(resetPolicy);
        RequiredArgumentBuilder<CommandSourceStack, String> templateName =
                Commands.argument("template", StringArgumentType.word());
        templateName.executes(ctx -> templateSave(ctx.getSource(),
                StringArgumentType.getString(ctx, "template"), StageTemplate.InstanceMode.PARALLEL,
                StageTemplate.ResetPolicy.ON_CREATE));
        templateName.then(instanceMode);
        templates.then(Commands.literal("save").then(templateName));
        root.then(templates);
        RequiredArgumentBuilder<CommandSourceStack, Integer> anchorZ =
                Commands.argument("z", IntegerArgumentType.integer());
        anchorZ.executes(ctx -> anchor(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "x"),
                IntegerArgumentType.getInteger(ctx, "y"), IntegerArgumentType.getInteger(ctx, "z")));
        RequiredArgumentBuilder<CommandSourceStack, Integer> anchorY =
                Commands.argument("y", IntegerArgumentType.integer());
        anchorY.then(anchorZ);
        RequiredArgumentBuilder<CommandSourceStack, Integer> anchorX =
                Commands.argument("x", IntegerArgumentType.integer());
        anchorX.then(anchorY);
        root.then(Commands.literal("anchor").then(anchorX));
        root.then(Commands.literal("backdrop")
                .then(Commands.literal("status").executes(ctx -> backdropStatus(ctx.getSource())))
                .then(Commands.literal("follow")
                        .then(Commands.literal("on").executes(ctx -> followPlayer(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> followPlayer(ctx.getSource(), false))))
                .then(Commands.literal("movement")
                        .then(Commands.argument("scale", DoubleArgumentType.doubleArg(
                                        StageClientScene.MIN_LOD_MOVEMENT_SCALE,
                                        StageClientScene.MAX_LOD_MOVEMENT_SCALE))
                                .executes(ctx -> movementScale(ctx.getSource(),
                                        (float) DoubleArgumentType.getDouble(ctx, "scale")))))
                .then(Commands.literal("dh-fade")
                        .then(Commands.argument("scale", DoubleArgumentType.doubleArg(
                                        StageClientScene.MIN_DH_NEAR_FADE_SCALE,
                                        StageClientScene.MAX_DH_NEAR_FADE_SCALE))
                                .executes(ctx -> dhNearFadeScale(ctx.getSource(),
                                        (float) DoubleArgumentType.getDouble(ctx, "scale")))))
                .then(backdropVisibility("show", true))
                .then(backdropVisibility("hide", false))
                .then(Commands.literal("blur")
                        .then(Commands.argument("radius", IntegerArgumentType.integer(
                                        0, (int) StageClientScene.MAX_BLUR_RADIUS))
                                .executes(ctx -> backdropBlur(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "radius"))))));
        LiteralArgumentBuilder<CommandSourceStack> time = Commands.literal("time");
        time.then(Commands.literal("status").executes(ctx -> timeStatus(ctx.getSource())));
        time.then(Commands.literal("follow").executes(ctx -> timeFollow(ctx.getSource())));
        time.then(Commands.literal("fixed")
                .then(Commands.argument("day_time", IntegerArgumentType.integer(0, 23_999))
                        .executes(ctx -> timeFixed(ctx.getSource(),
                                IntegerArgumentType.getInteger(ctx, "day_time")))));
        RequiredArgumentBuilder<CommandSourceStack, Integer> cyclePeriod =
                Commands.argument("period_ticks", IntegerArgumentType.integer(
                        (int) StageClientScene.MIN_TIME_CYCLE_TICKS,
                        (int) StageClientScene.MAX_TIME_CYCLE_TICKS));
        cyclePeriod.executes(ctx -> timeCycle(ctx.getSource(),
                IntegerArgumentType.getInteger(ctx, "period_ticks"), null));
        cyclePeriod.then(Commands.argument("start_day_time", IntegerArgumentType.integer(0, 23_999))
                .executes(ctx -> timeCycle(ctx.getSource(),
                        IntegerArgumentType.getInteger(ctx, "period_ticks"),
                        IntegerArgumentType.getInteger(ctx, "start_day_time"))));
        time.then(Commands.literal("cycle").then(cyclePeriod));
        root.then(time);
        root.then(Commands.literal("exit").executes(ctx -> exit(ctx.getSource())));
        root.then(Commands.literal("sky")
                .then(Commands.literal("overworld").executes(ctx -> sky(ctx.getSource(),
                        StageClientScene.SkyMode.OVERWORLD)))
                .then(Commands.literal("end").executes(ctx -> sky(ctx.getSource(), StageClientScene.SkyMode.END)))
                .then(Commands.literal("off").executes(ctx -> sky(ctx.getSource(), StageClientScene.SkyMode.OFF))));
        LiteralArgumentBuilder<CommandSourceStack> boundary = Commands.literal("boundary");
        boundary.then(Commands.literal("status").executes(ctx -> boundaryStatus(ctx.getSource())));
        boundary.then(Commands.literal("size")
                .then(Commands.argument("width", IntegerArgumentType.integer(
                                StageBoundary.MIN_HORIZONTAL_SIZE, StageBoundary.MAX_HORIZONTAL_SIZE))
                        .then(Commands.argument("depth", IntegerArgumentType.integer(
                                        StageBoundary.MIN_HORIZONTAL_SIZE, StageBoundary.MAX_HORIZONTAL_SIZE))
                                .then(Commands.argument("height", IntegerArgumentType.integer(
                                                StageBoundary.MIN_HEIGHT, StageBoundary.MAX_HEIGHT))
                                        .executes(ctx -> boundarySize(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "width"),
                                                IntegerArgumentType.getInteger(ctx, "depth"),
                                                IntegerArgumentType.getInteger(ctx, "height")))))));
        boundary.then(Commands.literal("color")
                .then(Commands.argument("rgb", StringArgumentType.word())
                        .executes(ctx -> boundaryColor(ctx.getSource(),
                                StringArgumentType.getString(ctx, "rgb")))));
        root.then(boundary);
        LiteralArgumentBuilder<CommandSourceStack> flight = Commands.literal("flight");
        RequiredArgumentBuilder<CommandSourceStack, String> flightScene =
                Commands.argument("scene", StringArgumentType.greedyString())
                        .suggests(DynamicStageCommands::suggestCMDCamScenes);
        flightScene.executes(ctx -> importCMDCamFlight(ctx.getSource(),
                StringArgumentType.getString(ctx, "stage"), StringArgumentType.getString(ctx, "scene")));
        flight.then(Commands.literal("import")
                .then(Commands.argument("stage", StringArgumentType.string()).then(flightScene)));
        flight.then(Commands.literal("importfile")
                .then(Commands.argument("stage", StringArgumentType.string())
                        .then(Commands.argument("file", StringArgumentType.greedyString())
                                .executes(ctx -> importCMDCamFileFlight(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "stage"),
                                        StringArgumentType.getString(ctx, "file"), null)))));
        flight.then(Commands.literal("importscene")
                .then(Commands.argument("stage", StringArgumentType.string())
                        .then(Commands.argument("file", StringArgumentType.string())
                                .then(Commands.argument("scene", StringArgumentType.greedyString())
                                        .executes(ctx -> importCMDCamFileFlight(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "stage"),
                                                StringArgumentType.getString(ctx, "file"),
                                                StringArgumentType.getString(ctx, "scene")))))));
        RequiredArgumentBuilder<CommandSourceStack, String> flightName =
                Commands.argument("name", StringArgumentType.word());
        flightName.suggests(DynamicStageCommands::suggestFlightImports);
        flightName.executes(ctx -> importJsonFlight(ctx.getSource(), StringArgumentType.getString(ctx, "stage"),
                StringArgumentType.getString(ctx, "name"), 1));
        flightName.then(Commands.argument("slot", IntegerArgumentType.integer(1, 10))
                .executes(ctx -> importJsonFlight(ctx.getSource(), StringArgumentType.getString(ctx, "stage"),
                        StringArgumentType.getString(ctx, "name"),
                        IntegerArgumentType.getInteger(ctx, "slot"))));
        flight.then(Commands.literal("importjson")
                .then(Commands.argument("stage", StringArgumentType.string()).then(flightName)));
        flight.then(Commands.literal("clear").then(Commands.argument("stage", StringArgumentType.string())
                .executes(ctx -> clearFlight(ctx.getSource(), StringArgumentType.getString(ctx, "stage")))));
        flight.then(Commands.literal("status").then(Commands.argument("stage", StringArgumentType.string())
                .executes(ctx -> flightStatus(ctx.getSource(), StringArgumentType.getString(ctx, "stage")))));
        RequiredArgumentBuilder<CommandSourceStack, String> libraryStage =
                Commands.argument("stage", StringArgumentType.string());
        libraryStage.executes(ctx -> useLibraryFlight(ctx.getSource(),
                StringArgumentType.getString(ctx, "stage"), StringArgumentType.getString(ctx, "stage")));
        libraryStage.then(Commands.argument("flight", StringArgumentType.word())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                        StageFlightAssets.listLibraryFlights(), builder))
                .executes(ctx -> useLibraryFlight(ctx.getSource(),
                        StringArgumentType.getString(ctx, "stage"),
                        StringArgumentType.getString(ctx, "flight"))));
        flight.then(Commands.literal("use").then(libraryStage));
        flight.then(Commands.literal("library").then(Commands.literal("list")
                .executes(ctx -> listLibraryFlights(ctx.getSource()))));
        root.then(flight);
        return root;
    }

    private static int start(CommandSourceStack source, String stage, ResourceLocation pack,
                             int x, int y, int z, int capacity) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        if (!StageSessionManager.createAndEnter(player, stage, pack, new BlockPos(x, y, z), capacity)) {
            source.sendFailure(Component.literal("Could not prepare the stage instance."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stage preparation requested with LOD pack " + pack + '.'), true);
        return 1;
    }

    private static int join(CommandSourceStack source, String rawInstance) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        try {
            UUID instanceId = UUID.fromString(rawInstance);
            if (!StageSessionManager.join(player, instanceId)) {
                source.sendFailure(Component.literal("Could not join that stage instance."));
                return 0;
            }
            return 1;
        } catch (IllegalArgumentException e) {
            source.sendFailure(Component.literal("Invalid stage instance UUID."));
            return 0;
        }
    }

    private static int templateSave(CommandSourceStack source, String id, StageTemplate.InstanceMode instanceMode,
                                    StageTemplate.ResetPolicy resetPolicy) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        StageSession session = StageSessionManager.get(player).orElse(null);
        if (session == null) {
            source.sendFailure(Component.literal("Enter and configure a stage before saving it as a template."));
            return 0;
        }
        try {
            StageTemplate template = StageTemplateStore.capture(source.getServer(), session, id,
                    instanceMode, resetPolicy);
            StageTemplateStore.save(template);
            source.sendSuccess(() -> Component.literal("Saved portable stage template '" + id + "' ("
                    + instanceMode.name().toLowerCase(java.util.Locale.ROOT) + ", capacity "
                    + template.capacity() + ")."), true);
            return 1;
        } catch (IOException | RuntimeException e) {
            source.sendFailure(Component.literal("Could not save stage template: " + e.getMessage()));
            return 0;
        }
    }

    private static int templateStart(CommandSourceStack source, String id) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        try {
            StageTemplate template = StageTemplateStore.load(id);
            if (template == null) {
                source.sendFailure(Component.literal("Unknown stage template '" + id + "'."));
                return 0;
            }
            if (!StageSessionManager.createAndEnterTemplate(player, template)) {
                source.sendFailure(Component.literal("Could not prepare stage template '" + id + "'."));
                return 0;
            }
            return 1;
        } catch (IOException | RuntimeException e) {
            source.sendFailure(Component.literal("Could not load stage template: " + e.getMessage()));
            return 0;
        }
    }

    private static int templateDelete(CommandSourceStack source, String id) {
        try {
            if (!StageTemplateStore.delete(id)) {
                source.sendFailure(Component.literal("Unknown stage template '" + id + "'."));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("Deleted stage template '" + id + "'."), true);
            return 1;
        } catch (IOException | RuntimeException e) {
            source.sendFailure(Component.literal("Could not delete stage template: " + e.getMessage()));
            return 0;
        }
    }

    private static int templateReset(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        StageSession session = StageSessionManager.get(player).orElse(null);
        if (session == null) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        try {
            StageTemplate template = StageTemplateStore.load(session.stageId());
            if (template == null || !StageSessionManager.resetTemplateArena(player, template)) {
                source.sendFailure(Component.literal("The active stage is not backed by a resettable template."));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("Reset the active arena from template '"
                    + template.id() + "'."), true);
            return 1;
        } catch (IOException | RuntimeException e) {
            source.sendFailure(Component.literal("Could not reset stage template: " + e.getMessage()));
            return 0;
        }
    }

    private static int templateList(CommandSourceStack source) {
        try {
            java.util.List<String> ids = StageTemplateStore.list();
            source.sendSuccess(() -> Component.literal(ids.isEmpty()
                    ? "No portable stage templates are saved."
                    : "Stage templates: " + String.join(", ", ids)), false);
            return ids.size();
        } catch (IOException | RuntimeException e) {
            source.sendFailure(Component.literal("Could not list stage templates: " + e.getMessage()));
            return 0;
        }
    }

    private static CompletableFuture<Suggestions> suggestTemplates(CommandContext<CommandSourceStack> context,
                                                                    SuggestionsBuilder builder) {
        try {
            return SharedSuggestionProvider.suggest(StageTemplateStore.list(), builder);
        } catch (IOException | RuntimeException e) {
            return builder.buildFuture();
        }
    }

    private static StageTemplate.InstanceMode parseInstanceMode(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "shared" -> StageTemplate.InstanceMode.SHARED;
            case "parallel" -> StageTemplate.InstanceMode.PARALLEL;
            default -> throw new IllegalArgumentException("instance_mode must be shared or parallel");
        };
    }

    private static StageTemplate.ResetPolicy parseResetPolicy(String value) {
        return switch (value.toLowerCase(java.util.Locale.ROOT)) {
            case "on_create" -> StageTemplate.ResetPolicy.ON_CREATE;
            case "manual" -> StageTemplate.ResetPolicy.MANUAL;
            default -> throw new IllegalArgumentException("reset_policy must be on_create or manual");
        };
    }

    private static int anchor(CommandSourceStack source, int x, int y, int z) {
        if (!(source.getEntity() instanceof ServerPlayer player)
                || !StageSessionManager.setAnchor(player, new BlockPos(x, y, z))) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Updated the instance LOD anchor."), true);
        return 1;
    }

    private static int followPlayer(CommandSourceStack source, boolean follow) {
        if (!(source.getEntity() instanceof ServerPlayer player)
                || !StageSessionManager.setFollowPlayer(player, follow)) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("LOD player movement follow: "
                + (follow ? "on" : "off") + '.'), true);
        return 1;
    }

    private static int movementScale(CommandSourceStack source, float scale) {
        if (!(source.getEntity() instanceof ServerPlayer player)
                || !StageSessionManager.setLodMovementScale(player, scale)) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("LOD player movement scale: " + scale + '.'), true);
        return 1;
    }

    private static int dhNearFadeScale(CommandSourceStack source, float scale) {
        if (!(source.getEntity() instanceof ServerPlayer player)
                || !StageSessionManager.setDhNearFadeScale(player, scale)) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("DH near fade scale: " + scale + '.'), true);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> backdropVisibility(String name, boolean visible) {
        return Commands.literal(name)
                .executes(ctx -> backdropVisibility(ctx.getSource(), visible,
                        StageClientScene.Transition.INSTANT, 0))
                .then(Commands.literal("fade")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(
                                        1, StageClientScene.MAX_TRANSITION_TICKS))
                                .executes(ctx -> backdropVisibility(ctx.getSource(), visible,
                                        StageClientScene.Transition.FADE,
                                        IntegerArgumentType.getInteger(ctx, "ticks")))))
                .then(Commands.literal("blur")
                        .then(Commands.argument("ticks", IntegerArgumentType.integer(
                                        1, StageClientScene.MAX_TRANSITION_TICKS))
                                .executes(ctx -> backdropVisibility(ctx.getSource(), visible,
                                        StageClientScene.Transition.BLUR,
                                        IntegerArgumentType.getInteger(ctx, "ticks")))));
    }

    private static int backdropVisibility(CommandSourceStack source, boolean visible,
                                            StageClientScene.Transition transition, int ticks) {
        if (!(source.getEntity() instanceof ServerPlayer player)
                || !StageSessionManager.setLodVisible(player, visible, transition, ticks)) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stage LOD " + (visible ? "shown" : "hidden")
                + (transition == StageClientScene.Transition.INSTANT ? "."
                : " with " + transition.name().toLowerCase(java.util.Locale.ROOT)
                + " over " + ticks + " ticks.")), true);
        return 1;
    }

    private static int backdropBlur(CommandSourceStack source, int radius) {
        if (!(source.getEntity() instanceof ServerPlayer player)
                || !StageSessionManager.setLodBlur(player, radius)) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stage LOD persistent blur radius: " + radius + '.'), true);
        return 1;
    }

    private static int backdropStatus(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        StageSession session = StageSessionManager.get(player).orElse(null);
        if (session == null) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        StageClientScene scene = session.clientScene();
        source.sendSuccess(() -> Component.literal("Stage backdrop: follow_player=" + scene.followPlayer()
                + ", movement_scale=" + scene.lodMovementScale()
                + ", dh_fade_scale=" + scene.dhNearFadeScale()
                + ", visible=" + scene.lodVisible() + ", blur=" + scene.lodBlurRadius()
                + ", transition=" + scene.lodTransition().name().toLowerCase(java.util.Locale.ROOT) + '.'), false);
        return 1;
    }

    private static int timeStatus(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        StageSession session = StageSessionManager.get(player).orElse(null);
        if (session == null) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        StageClientScene scene = session.clientScene();
        source.sendSuccess(() -> Component.literal("Stage client time: "
                + scene.timeMode().name().toLowerCase(java.util.Locale.ROOT)
                + ", base=" + scene.timeBaseDayTime()
                + (scene.timeMode() == StageClientScene.TimeMode.CYCLE
                ? ", period=" + scene.timeCycleTicks() + " ticks." : ".")), false);
        return 1;
    }

    private static int timeFollow(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        long dayTime = source.getServer().overworld().getDayTime();
        if (!StageSessionManager.setClientTime(player, StageClientScene.TimeMode.FOLLOW, dayTime, 0L)) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stage client time follows the Overworld."), true);
        return 1;
    }

    private static int timeFixed(CommandSourceStack source, int dayTime) {
        if (!(source.getEntity() instanceof ServerPlayer player)
                || !StageSessionManager.setClientTime(player, StageClientScene.TimeMode.FIXED, dayTime, 0L)) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stage client time fixed at " + dayTime + '.'), true);
        return 1;
    }

    private static int timeCycle(CommandSourceStack source, int periodTicks, Integer startDayTime) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        long start = startDayTime == null
                ? Math.floorMod(source.getServer().overworld().getDayTime(), 24_000L) : startDayTime;
        if (!StageSessionManager.setClientTime(player, StageClientScene.TimeMode.CYCLE, start, periodTicks)) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stage client day cycle: " + periodTicks
                + " ticks, starting at " + start + '.'), true);
        return 1;
    }

    private static int exit(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player) || !StageSessionManager.exit(player)) {
            source.sendFailure(Component.literal("No active or preparing stage session."));
            return 0;
        }
        return 1;
    }

    private static int sky(CommandSourceStack source, StageClientScene.SkyMode mode) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        if (!StageSessionManager.setSkyMode(player, mode)) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Dynamic Stage sky: "
                + mode.name().toLowerCase(java.util.Locale.ROOT)), false);
        return 1;
    }

    private static int boundaryStatus(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        StageSession session = StageSessionManager.get(player).orElse(null);
        if (session == null) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        StageBoundary boundary = session.boundary();
        source.sendSuccess(() -> Component.literal("Stage boundary: " + boundary.width() + " x "
                + boundary.depth() + " x " + boundary.height() + ", color #"
                + String.format(java.util.Locale.ROOT, "%06X", boundary.color()) + '.'), false);
        return 1;
    }

    private static int boundarySize(CommandSourceStack source, int width, int depth, int height) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        StageSession session = StageSessionManager.get(player).orElse(null);
        if (session == null || !StageSessionManager.setBoundary(player,
                new StageBoundary(width, depth, height, session.boundary().color()))) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stage boundary resized to " + width + " x "
                + depth + " x " + height + '.'), true);
        return 1;
    }

    private static int boundaryColor(CommandSourceStack source, String value) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        StageSession session = StageSessionManager.get(player).orElse(null);
        if (session == null) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        String digits = value.startsWith("#") ? value.substring(1)
                : value.startsWith("0x") || value.startsWith("0X") ? value.substring(2) : value;
        final int color;
        try {
            if (digits.length() != 6) {
                throw new NumberFormatException();
            }
            color = Integer.parseInt(digits, 16);
        } catch (NumberFormatException e) {
            source.sendFailure(Component.literal("Boundary color must be a six-digit RGB value, for example FF4858."));
            return 0;
        }
        if (!StageSessionManager.setBoundary(player, session.boundary().withColor(color))) {
            source.sendFailure(Component.literal("Could not update the stage boundary."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stage boundary color set to #"
                + String.format(java.util.Locale.ROOT, "%06X", color) + '.'), true);
        return 1;
    }

    private static int importCMDCamFlight(CommandSourceStack source, String stage, String scene) {
        try {
            Path candidate = Path.of(scene).toAbsolutePath().normalize();
            if (Files.isRegularFile(candidate)) {
                return importCMDCamFileFlight(source, stage, scene, null);
            }
        } catch (RuntimeException ignored) {
            // Treat non-path arguments as saved scene names.
        }
        Path worldRoot = source.getServer().getWorldPath(LevelResource.ROOT);
        try {
            StageFlightAssets.Asset asset = StageFlightAssets.importFromCMDCam(source.getLevel(),
                    worldRoot, stage, scene);
            source.sendSuccess(() -> Component.literal("Imported CMDCam scene '" + scene + "' for '" + stage
                    + "': " + asset.pointCount() + " points, " + asset.durationMillis() + " ms."), true);
            return 1;
        } catch (IOException | RuntimeException e) {
            source.sendFailure(Component.literal("Could not import CMDCam scene: " + e.getMessage()));
            return 0;
        }
    }

    private static int importCMDCamFileFlight(CommandSourceStack source, String stage,
                                               String file, String scene) {
        Path worldRoot = source.getServer().getWorldPath(LevelResource.ROOT);
        try {
            StageFlightAssets.Asset asset = StageFlightAssets.importFromCMDCamFile(
                    Path.of(file), worldRoot, stage, scene);
            source.sendSuccess(() -> Component.literal("Imported external CMDCam flight for '" + stage
                    + "': " + asset.pointCount() + " points, " + asset.durationMillis() + " ms."), true);
            return 1;
        } catch (IOException | RuntimeException e) {
            source.sendFailure(Component.literal("Could not import external CMDCam scene: " + e.getMessage()));
            return 0;
        }
    }

    private static int importJsonFlight(CommandSourceStack source, String stage, String name, int slot) {
        Path worldRoot = source.getServer().getWorldPath(LevelResource.ROOT);
        try {
            StageFlightAssets.Asset asset = StageFlightAssets.importFromInbox(worldRoot, stage, name, slot);
            source.sendSuccess(() -> Component.literal("Imported CMDCam flight for '" + stage + "': "
                    + asset.pointCount() + " points, " + asset.durationMillis() + " ms."), true);
            return 1;
        } catch (IOException | RuntimeException e) {
            source.sendFailure(Component.literal("Could not import stage flight: " + e.getMessage()));
            return 0;
        }
    }

    private static int useLibraryFlight(CommandSourceStack source, String stage, String flightName) {
        Path worldRoot = source.getServer().getWorldPath(LevelResource.ROOT);
        try {
            StageFlightAssets.Asset asset = StageFlightAssets.importFromLibrary(worldRoot, stage, flightName);
            source.sendSuccess(() -> Component.literal("Installed global flight '" + flightName + "' for '"
                    + stage + "': " + asset.pointCount() + " points, " + asset.durationMillis() + " ms."), true);
            return 1;
        } catch (IOException | RuntimeException e) {
            source.sendFailure(Component.literal("Could not install global flight: " + e.getMessage()));
            return 0;
        }
    }

    private static int listLibraryFlights(CommandSourceStack source) {
        java.util.List<String> names = StageFlightAssets.listLibraryFlights();
        source.sendSuccess(() -> Component.literal(names.isEmpty()
                ? "No global Dynamic Stage flights are saved."
                : "Global Dynamic Stage flights: " + String.join(", ", names)), false);
        return names.size();
    }

    private static CompletableFuture<Suggestions> suggestCMDCamScenes(CommandContext<CommandSourceStack> context,
                                                                       SuggestionsBuilder builder) {
        Path worldRoot = context.getSource().getServer().getWorldPath(LevelResource.ROOT);
        return SharedSuggestionProvider.suggest(StageFlightAssets.listCMDCamScenes(
                context.getSource().getLevel(), worldRoot), builder);
    }

    private static CompletableFuture<Suggestions> suggestFlightImports(CommandContext<CommandSourceStack> context,
                                                                        SuggestionsBuilder builder) {
        Path worldRoot = context.getSource().getServer().getWorldPath(LevelResource.ROOT);
        Path inbox = StageFlightAssets.inboxDirectory(worldRoot);
        if (!java.nio.file.Files.isDirectory(inbox)) {
            return builder.buildFuture();
        }
        try (var files = java.nio.file.Files.list(inbox)) {
            return SharedSuggestionProvider.suggest(files
                    .filter(path -> java.nio.file.Files.isRegularFile(path))
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".json"))
                    .map(name -> name.substring(0, name.length() - ".json".length()))
                    .filter(name -> name.matches("[A-Za-z0-9_-]{1,64}"))
                    .sorted()
                    .toList(), builder);
        } catch (IOException e) {
            return builder.buildFuture();
        }
    }

    private static int clearFlight(CommandSourceStack source, String stage) {
        try {
            if (!StageFlightAssets.clear(source.getServer().getWorldPath(LevelResource.ROOT), stage)) {
                source.sendFailure(Component.literal("Stage '" + stage + "' has no configured flight."));
                return 0;
            }
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not clear stage flight: " + e.getMessage()));
            return 0;
        }
    }

    private static int flightStatus(CommandSourceStack source, String stage) {
        StageFlightAssets.Asset asset = StageFlightAssets.findConfigured(
                source.getServer().getWorldPath(LevelResource.ROOT), stage);
        if (asset == null) {
            source.sendFailure(Component.literal("Stage '" + stage + "' has no valid configured flight."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stage flight: " + asset.pointCount() + " points, "
                + asset.durationMillis() + " ms, hash=" + asset.hash() + '.'), false);
        return 1;
    }
}
