package vibe.liteming.dynamicstage.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import vibe.liteming.dynamicstage.client.lod.StageLodCompositor;

/** Substitutes only Voxy's final LOD color texture; its depth reprojection remains native. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.NormalRenderPipeline", remap = false)
public abstract class VoxyCompositeMixin {
    @ModifyArg(method = "finish", at = @At(value = "INVOKE",
            target = "Lorg/lwjgl/opengl/GL45C;glBindTextureUnit(II)V"),
            index = 1, require = 0, remap = false)
    private int dynamicstage$filterLodColor(int sourceTexture) {
        return StageLodCompositor.filterColorTexture(sourceTexture);
    }
}
