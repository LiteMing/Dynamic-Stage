package vibe.liteming.dynamicstage.fabric;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.InteractionResult;
import vibe.liteming.dynamicstage.command.DynamicStageCommands;
import vibe.liteming.dynamicstage.stage.StageSessionManager;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class FabricStageEvents {
    private static final Map<UUID, Integer> RESPAWN_EXITS = new ConcurrentHashMap<>();

    private FabricStageEvents() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, selection) ->
                DynamicStageCommands.register(dispatcher));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            RESPAWN_EXITS.clear();
            StageSessionManager.onServerStopped();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                StageSessionManager.restore(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                StageSessionManager.onLogout(handler.player));
        ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register((player, origin, destination) -> {
            if (StageWorlds.isStageLevel(origin) && !StageWorlds.isStageLevel(destination)
                    && StageSessionManager.get(player).isPresent()) {
                player.getServer().execute(() -> StageSessionManager.releaseWithoutTeleport(player));
            }
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (StageSessionManager.get(newPlayer).isPresent()) {
                // Cross-dimension teleporting inside Fabric's respawn callback
                // can add the replacement player to two worlds at once.
                RESPAWN_EXITS.put(newPlayer.getUUID(), 1);
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            StageSessionManager.tick(server);
            RESPAWN_EXITS.forEach((playerId, delay) -> {
                if (delay > 0) {
                    RESPAWN_EXITS.replace(playerId, delay, delay - 1);
                    return;
                }
                if (!RESPAWN_EXITS.remove(playerId, delay)) {
                    return;
                }
                var player = server.getPlayerList().getPlayer(playerId);
                if (player != null) {
                    StageSessionManager.exit(player);
                }
            });
            server.getPlayerList().getPlayers().forEach(StageSessionManager::enforceBoundary);
        });

        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
                !level.isClientSide && StageWorlds.isStageLevel(level) && !StageSessionManager.isEditing(player, pos)
                        ? InteractionResult.FAIL : InteractionResult.PASS);
        UseBlockCallback.EVENT.register((player, level, hand, hit) ->
                !level.isClientSide && StageWorlds.isStageLevel(level)
                        ? useStageBlock(player, level, hand, hit) : InteractionResult.PASS);
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) ->
                level.isClientSide || !StageWorlds.isStageLevel(level) || StageSessionManager.isEditing(player, pos));
    }

    private static InteractionResult useStageBlock(net.minecraft.world.entity.player.Player player,
                                                   net.minecraft.world.level.Level level,
                                                   net.minecraft.world.InteractionHand hand,
                                                   net.minecraft.world.phys.BlockHitResult hit) {
        if (StageSessionManager.isEditing(player, hit.getBlockPos())) {
            return InteractionResult.PASS;
        }
        if (!StageSessionManager.canUseBlock(player, hit.getBlockPos())) {
            return InteractionResult.FAIL;
        }
        InteractionResult result = level.getBlockState(hit.getBlockPos()).use(level, player, hand, hit);
        return result.consumesAction() ? result : InteractionResult.FAIL;
    }
}
