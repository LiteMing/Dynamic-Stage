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
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.nio.file.Path;

/**
 * Minimal stage commands:
 * <pre>
 * /dynamicstage start <stage> dim <x> <y> <z> [voxy|dh]
 *     → resolve the world's LOD data (Voxy RocksDB or Distant Horizons sqlite),
 *       record the anchor, teleport into the stage dimension; the client then
 *       renders the LOD directly (no bake).
 * /dynamicstage exit → teleport back to overworld spawn
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
            dataFile = DHStorageLocator.locate(player.getServer());
            if (dataFile == null) {
                source.sendFailure(Component.literal("No DistantHorizons.sqlite found for this world."));
                return 0;
            }
        } else {
            dataFile = VoxyStorageLocator.locate(player.getServer());
            if (dataFile == null) {
                source.sendFailure(Component.literal("No Voxy storage found under this world's voxy/ directory."));
                return 0;
            }
            src = StageSession.SOURCE_VOXY;
        }
        final String resolvedSource = src;
        BlockPos anchor = new BlockPos(x, y, z);
        StageSession.set(anchor, stage, dataFile, resolvedSource);
        player.teleportTo(player.getServer().getLevel(StageWorlds.STG_STAGE), x + 0.5D, y, z + 0.5D,
                player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal("Stage '" + stage + "' anchored at (" + x + ", " + y + ", " + z
                + ") source=" + resolvedSource + ". LOD will render on arrival."), true);
        return 1;
    }

    private static int exit(CommandSourceStack source) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return 0;
        }
        BlockPos spawn = player.getServer().overworld().getSharedSpawnPos();
        player.teleportTo(player.getServer().overworld(), spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        StageSession.reset();
        source.sendSuccess(() -> Component.literal("Returned to overworld."), true);
        return 1;
    }
}
