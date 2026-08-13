package vibe.liteming.dynamicstage.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageSessionManager;

import java.io.IOException;
import java.nio.file.Path;
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
        root.then(Commands.literal("exit").executes(ctx -> exit(ctx.getSource())));
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

    private static int anchor(CommandSourceStack source, int x, int y, int z) {
        if (!(source.getEntity() instanceof ServerPlayer player)
                || !StageSessionManager.setAnchor(player, new BlockPos(x, y, z))) {
            source.sendFailure(Component.literal("No active stage instance."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Updated the instance LOD anchor."), true);
        return 1;
    }

    private static int exit(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player) || !StageSessionManager.exit(player)) {
            source.sendFailure(Component.literal("No active or preparing stage session."));
            return 0;
        }
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
