package vibe.liteming.dynamicstage.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vibe.liteming.dynamicstage.client.lod.DhVirtualCamera;

/** Keeps DH's stage projection near plane independent from its fade uniform. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.util.RenderUtil", remap = false)
public abstract class DhProjectionMixin {
    @Inject(method = "getNearClipPlaneDistanceInBlocks(F)F", at = @At("RETURN"),
            cancellable = true, require = 1, remap = false)
    private static void dynamicstage$scaleStageNearClip(float partialTicks,
                                                          CallbackInfoReturnable<Float> callback) {
        callback.setReturnValue(DhVirtualCamera.scaleNearClip(callback.getReturnValueF()));
    }
}
