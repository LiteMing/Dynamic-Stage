package vibe.liteming.dynamicstage.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import vibe.liteming.dynamicstage.client.lod.DhVirtualCamera;

/** Removes DH's vanilla-chunk handoff hole while an external stage backdrop is active. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram_forge",
        remap = false)
public abstract class DhNearClipMixin {

    @ModifyArg(method = "fillUniformData",
            at = @At(value = "INVOKE",
                    target = "Lcom/seibel/distanthorizons/common/render/openGl/terrain/"
                            + "GlDhTerrainShaderProgram_forge;setUniform(IF)V",
                    ordinal = 4),
            index = 1,
            require = 1,
            remap = false)
    private float dynamicstage$renderLodUpToStageCamera(float original) {
        return DhVirtualCamera.position() == null ? original : 0.01F;
    }
}
