package vibe.liteming.dynamicstage.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import vibe.liteming.dynamicstage.client.StageClientTime;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.world.StageWorlds;

@Mixin(ClientLevel.class)
public abstract class StageClientTimeMixin {
    /** Overrides LevelAccessor's default implementation only for the stage client level. */
    public long dayTime() {
        ClientLevel level = (ClientLevel) (Object) this;
        ClientStageSession.Snapshot snapshot = ClientStageSession.active();
        if (!StageWorlds.isStageLevel(level) || snapshot == null) {
            return level.getLevelData().getDayTime();
        }
        return StageClientTime.dayTime(snapshot.clientScene(), level.getGameTime());
    }
}
