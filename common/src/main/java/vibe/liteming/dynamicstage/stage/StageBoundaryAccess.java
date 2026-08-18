package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.util.UUID;

/** Side-safe lookup used by the shared entity collision Mixin. */
public final class StageBoundaryAccess {
    private static volatile ClientBoundary clientBoundary;

    private StageBoundaryAccess() {
    }

    public static Located find(Entity entity) {
        if (entity == null || entity.isSpectator() || !StageWorlds.isStageLevel(entity.level())) {
            return null;
        }
        if (!entity.level().isClientSide && entity.level() instanceof ServerLevel serverLevel) {
            if (entity instanceof ServerPlayer player) {
                StageSession session = StageSessionManager.get(player).orElse(null);
                return session == null ? null : new Located(session.stageOrigin(), session.boundary());
            }
            StageInstance instance = StageSessionData.get(serverLevel.getServer())
                    .findRegion(entity.getX(), entity.getZ()).orElse(null);
            return instance == null ? null : new Located(instance.stageOrigin(), instance.boundary());
        }
        ClientBoundary local = clientBoundary;
        return entity.level().isClientSide && local != null && entity.getUUID().equals(local.playerId)
                ? new Located(local.origin, local.boundary)
                : null;
    }

    public static void setClient(UUID instanceId, BlockPos origin, StageBoundary boundary) {
        ClientBoundary current = clientBoundary;
        // Keep the bound player while a session update is applied. Clearing it
        // creates a one-tick client collision hole exactly when commands update
        // an otherwise unrelated client setting.
        UUID playerId = current == null ? null : current.playerId;
        clientBoundary = new ClientBoundary(instanceId, playerId, origin, boundary);
    }

    public static void bindClientPlayer(UUID playerId) {
        ClientBoundary current = clientBoundary;
        if (current != null && !playerId.equals(current.playerId)) {
            clientBoundary = new ClientBoundary(current.instanceId, playerId, current.origin, current.boundary);
        }
    }

    public static void clearClient() {
        clientBoundary = null;
    }

    public record Located(BlockPos origin, StageBoundary boundary) {
    }

    private record ClientBoundary(UUID instanceId, UUID playerId, BlockPos origin, StageBoundary boundary) {
    }
}
