package vibe.liteming.dynamicstage.forge.stage;

import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.TickEvent;
import vibe.liteming.dynamicstage.DynamicStage;
import vibe.liteming.dynamicstage.stage.StageSessionManager;
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
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            StageSessionManager.tick(event.getServer());
            event.getServer().getPlayerList().getPlayers().forEach(StageSessionManager::enforceBoundary);
        }
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
                && !event.getTo().equals(StageWorlds.STG_STAGE)
                && StageSessionManager.get(player).isPresent()) {
            // DH also consumes this event. Defer its level teardown until every listener has observed the transition.
            player.getServer().execute(() -> StageSessionManager.releaseWithoutTeleport(player));
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
        if (event.getLevel().isClientSide()
                || !StageWorlds.isStageLevel(event.getLevel())
                || event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && StageSessionManager.isEditing(player)) {
            return;
        }
        event.setCancellationResult(InteractionResult.PASS);
        event.setUseBlock(Event.Result.DENY);
        event.setUseItem(Event.Result.DENY);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!event.getLevel().isClientSide() && StageWorlds.isStageLevel(event.getLevel())
                && !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && StageSessionManager.isEditing(player))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof Level level && !level.isClientSide
                && StageWorlds.isStageLevel(level)
                && !(event.getPlayer() instanceof net.minecraft.server.level.ServerPlayer player
                && StageSessionManager.isEditing(player))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof Level level && !level.isClientSide
                && StageWorlds.isStageLevel(level)
                && !(event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player
                && StageSessionManager.isEditing(player))) {
            event.setCanceled(true);
        }
    }
}
