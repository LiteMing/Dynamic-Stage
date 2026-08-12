package vibe.liteming.dynamicstage.stage;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.server.ServerStoppedEvent;
import vibe.liteming.dynamicstage.DynamicStage;
import vibe.liteming.dynamicstage.world.StageWorlds;

/** Server-side interaction isolation for the backdrop-only stage dimension. */
@Mod.EventBusSubscriber(modid = DynamicStage.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StageForgeEvents {

    private StageForgeEvents() {
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        StageSessionManager.onServerStopped();
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            StageSessionManager.restore(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
            StageSessionManager.onLogout(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && event.getFrom().equals(StageWorlds.STG_STAGE)
                && !event.getTo().equals(StageWorlds.STG_STAGE)) {
            StageSessionManager.releaseWithoutTeleport(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && StageSessionManager.get(player).isPresent()) {
            StageSessionManager.exit(player);
        }
    }

    @SubscribeEvent
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!StageWorlds.isStageLevel(event.getLevel())) {
            return;
        }
        event.setCancellationResult(InteractionResult.PASS);
        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (StageWorlds.isStageLevel(event.getLevel())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level && StageWorlds.isStageLevel(level)) {
            event.setCanceled(true);
        }
    }
}
