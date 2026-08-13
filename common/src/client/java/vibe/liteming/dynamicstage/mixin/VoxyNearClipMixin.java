package vibe.liteming.dynamicstage.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import vibe.liteming.dynamicstage.client.lod.VoxyBackdropRuntime;

/** Lets an external Voxy backdrop render through the empty vanilla stage foreground. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public abstract class VoxyNearClipMixin {

    @Redirect(method = "computeProjectionMat(Lorg/joml/Matrix4fc;)Lorg/joml/Matrix4f;",
            at = @At(value = "INVOKE",
                    target = "Lme/cortex/voxy/client/VoxyClient;disableSodiumChunkRender()Z"),
            require = 1,
            remap = false)
    private static boolean dynamicstage$renderUpToStageCamera() {
        // Voxy 0.2.14 normally returns false here. Returning true selects its
        // 0.1 near plane without changing Sodium's own chunk renderer.
        return VoxyBackdropRuntime.shouldOverrideCurrentStage();
    }

    @Redirect(method = "renderOpaque(Lme/cortex/voxy/client/core/rendering/Viewport;)V",
            at = @At(value = "INVOKE",
                    target = "Lme/cortex/voxy/client/VoxyClient;disableSodiumChunkRender()Z"),
            require = 1,
            remap = false)
    private boolean dynamicstage$skipEmptyStageChunkBounds() {
        // The stage can still render its real Sodium chunks. Only Voxy's depth
        // mask for the vanilla/LOD handoff is disabled because the foreground
        // is intentionally sparse or empty.
        return VoxyBackdropRuntime.shouldOverrideCurrentStage();
    }
}
