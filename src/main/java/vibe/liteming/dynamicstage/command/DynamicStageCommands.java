package vibe.liteming.dynamicstage.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import vibe.liteming.dynamicstage.bake.DHStorageLocator;
import vibe.liteming.dynamicstage.bake.VoxyStorageLocator;
import vibe.liteming.dynamicstage.flight.StageFlightAssets;
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.stage.StageSessionManager;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Minimal stage commands:
 * <pre>
 * /dynamicstage start <stage> dim <x> <y> <z> [voxy|dh]
 *     → resolve the world's LOD data, prepare/cache a portable backdrop, then
 *       teleport into an isolated stage region and distribute it to the client.
 * /dynamicstage exit → restore the exact pre-stage dimension and pose
 * /dynamicstage flight import <stage> <name> [slot]
 *     → select a scene from data/dynamicstage/flight_imports/name.json
 * </pre>
 */
public final class DynamicStageCommands {

    private DynamicStageCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dynamicstage")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("start")
                        .then(Commands.argument("stage", StringArgumentType.string())
                                .then(Commands.literal("dim")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                                .executes(ctx -> start(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "stage"),
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"),
                                                                        null))
                                                                .then(Commands.argument("source", StringArgumentType.string())
                                                                        .executes(ctx -> start(ctx.getSource(),
                                                                                StringArgumentType.getString(ctx, "stage"),
                                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                                StringArgumentType.getString(ctx, "source"))))))))))
                .then(Commands.literal("exit")
                        .executes(ctx -> exit(ctx.getSource())))
                .then(Commands.literal("flight")
                        .then(Commands.literal("import")
                                .then(Commands.argument("stage", StringArgumentType.string())
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> importFlight(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "stage"),
                                                        StringArgumentType.getString(ctx, "name"), 1))
                                                .then(Commands.argument("slot", IntegerArgumentType.integer(1, 10))
                                                        .executes(ctx -> importFlight(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "stage"),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                IntegerArgumentType.getInteger(ctx, "slot")))))))
                        .then(Commands.literal("clear")
                                .then(Commands.argument("stage", StringArgumentType.string())
                                        .executes(ctx -> clearFlight(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "stage")))))
                        .then(Commands.literal("status")
                                .then(Commands.argument("stage", StringArgumentType.string())
                                        .executes(ctx -> flightStatus(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "stage")))))));
    }

    private static int start(CommandSourceStack source, String stage, int x, int y, int z, String sourceName) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        String src = sourceName == null ? StageSession.SOURCE_VOXY : sourceName;
        Path dataFile;
        if (StageSession.SOURCE_DH.equals(src)) {
            dataFile = DHStorageLocator.locate(player.getServer(), player.serverLevel());
            if (dataFile == null) {
                source.sendFailure(Component.literal("No DistantHorizons.sqlite found for the current dimension."));
                return 0;
            }
        } else {
            dataFile = VoxyStorageLocator.locate(player.getServer(), player.serverLevel());
            if (dataFile == null) {
                source.sendFailure(Component.literal("No Voxy storage found for the current dimension and seed."));
                return 0;
            }
            src = StageSession.SOURCE_VOXY;
        }
        final String resolvedSource = src;
        BlockPos anchor = new BlockPos(x, y, z);
        if (!StageSessionManager.prepareAndEnter(player, stage, anchor, dataFile, resolvedSource)) {
            source.sendFailure(Component.literal("Could not start the stage session."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stage '" + stage + "' queued at (" + x + ", " + y + ", " + z
                + ") source=" + resolvedSource + "."), true);
        return 1;
    }

    private static int exit(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        if (!StageSessionManager.exit(player)) {
            source.sendFailure(Component.literal("No active stage session."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stage preparation cancelled or player returned."), true);
        return 1;
    }

    private static int importFlight(CommandSourceStack source, String stage, String name, int slot) {
        Path worldRoot = source.getServer().getWorldPath(LevelResource.ROOT);
        try {
            StageFlightAssets.Asset asset = StageFlightAssets.importFromInbox(worldRoot, stage, name, slot);
            source.sendSuccess(() -> Component.literal("Imported CMDCam slot " + slot + " for stage '" + stage
                    + "': " + asset.pointCount() + " points, " + asset.durationMillis() + " ms, hash="
                    + asset.hash() + "."), true);
            return 1;
        } catch (IOException | RuntimeException e) {
            source.sendFailure(Component.literal("Could not import stage flight: " + e.getMessage()));
            return 0;
        }
    }

    private static int clearFlight(CommandSourceStack source, String stage) {
        Path worldRoot = source.getServer().getWorldPath(LevelResource.ROOT);
        try {
            if (!StageFlightAssets.clear(worldRoot, stage)) {
                source.sendFailure(Component.literal("Stage '" + stage + "' has no configured flight."));
                return 0;
            }
            source.sendSuccess(() -> Component.literal("Cleared the configured flight for stage '" + stage + "'."),
                    true);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not clear stage flight: " + e.getMessage()));
            return 0;
        }
    }

    private static int flightStatus(CommandSourceStack source, String stage) {
        Path worldRoot = source.getServer().getWorldPath(LevelResource.ROOT);
        StageFlightAssets.Asset asset = StageFlightAssets.findConfigured(worldRoot, stage);
        if (asset == null) {
            source.sendFailure(Component.literal("Stage '" + stage + "' has no valid configured flight. Import files from "
                    + StageFlightAssets.inboxDirectory(worldRoot) + '.'));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Stage '" + stage + "' flight: " + asset.pointCount()
                + " points, " + asset.durationMillis() + " ms, " + asset.bytes() + " bytes, hash="
                + asset.hash() + "."), false);
        return 1;
    }
}
