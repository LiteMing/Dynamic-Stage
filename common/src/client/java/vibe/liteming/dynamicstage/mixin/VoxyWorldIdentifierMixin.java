package vibe.liteming.dynamicstage.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vibe.liteming.dynamicstage.client.lod.VoxyBackdropRuntime;

@Pseudo
@Mixin(targets = "me.cortex.voxy.commonImpl.WorldIdentifier", remap = false)
public abstract class VoxyWorldIdentifierMixin {
    @Inject(method = "getWorldId()Ljava/lang/String;", at = @At("HEAD"),
            cancellable = true, require = 0, remap = false)
    private void dynamicstage$externalWorldId(CallbackInfoReturnable<String> callback) {
        String worldId = VoxyBackdropRuntime.selectedWorldId();
        if (worldId != null && VoxyBackdropRuntime.shouldOverrideCurrentStage()) {
            callback.setReturnValue(worldId);
        }
    }
}
