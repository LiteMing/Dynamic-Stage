package vibe.liteming.dynamicstage.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import vibe.liteming.dynamicstage.bake.DHStorageLocator;
import vibe.liteming.dynamicstage.bake.VoxyStorageLocator;
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.stage.StageSessionManager;

import java.nio.file.Path;

/**
 * Minimal stage commands:
 * <pre>
 * /dynamicstage start <stage> dim <x> <y> <z> [voxy|dh]
 *     → resolve the world's LOD data, prepare/cache a portable backdrop, then
 *       teleport into an isolated stage region and distribute it to the client.
 * /dynamicstage exit → restore the exact pre-stage dimension and pose
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
                        .executes(ctx -> exit(ctx.getSource()))));
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
}
