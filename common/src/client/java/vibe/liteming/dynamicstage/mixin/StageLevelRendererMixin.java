package vibe.liteming.dynamicstage.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vibe.liteming.dynamicstage.client.lod.VoxyBackdropRuntime;
import vibe.liteming.dynamicstage.world.StageWorlds;

@Mixin(value = LevelRenderer.class, priority = 2000)
public abstract class StageLevelRendererMixin {
    @Inject(method = "setLevel", at = @At("HEAD"))
    private void dynamicstage$releaseVoxyStageStorage(ClientLevel level, CallbackInfo callback) {
        if (level == null || !StageWorlds.isStageLevel(level)) {
            VoxyBackdropRuntime.leaveStageLevel();
        }
    }
}
