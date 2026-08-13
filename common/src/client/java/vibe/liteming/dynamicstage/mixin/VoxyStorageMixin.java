package vibe.liteming.dynamicstage.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vibe.liteming.dynamicstage.client.lod.VoxyBackdropRuntime;

import java.nio.file.Path;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.VoxyClientInstance", remap = false)
public abstract class VoxyStorageMixin {
    @Inject(method = "getBasePath()Ljava/nio/file/Path;", at = @At("HEAD"),
            cancellable = true, require = 1, remap = false)
    private static void dynamicstage$externalBasePath(CallbackInfoReturnable<Path> callback) {
        Path path = VoxyBackdropRuntime.selectedBasePath();
        if (path != null && VoxyBackdropRuntime.shouldOverrideCurrentStage()) {
            callback.setReturnValue(path);
        }
    }

    @Inject(method = "isIngestEnabled(Lme/cortex/voxy/commonImpl/WorldIdentifier;)Z",
            at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void dynamicstage$disableStageIngest(CallbackInfoReturnable<Boolean> callback) {
        if (VoxyBackdropRuntime.shouldOverrideCurrentStage()) {
            callback.setReturnValue(false);
        }
    }
}
