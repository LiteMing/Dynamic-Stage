package vibe.liteming.dynamicstage.mixin;

import org.lwjgl.opengl.GL14C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vibe.liteming.dynamicstage.client.lod.StageLodCompositor;

import java.lang.reflect.Method;

/** Filters DH's final LOD color attachment while retaining its original depth application shader. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.render.renderer.shaders.DhApplyShader", remap = false)
public abstract class DhCompositeMixin {
    @Inject(method = {"renderToFrameBuffer", "renderToMcTexture"}, at = @At("HEAD"),
            cancellable = true, require = 0, remap = false)
    private void dynamicstage$skipHiddenLod(CallbackInfo callback) {
        if (StageLodCompositor.shouldSkipComposite()) {
            callback.cancel();
        }
    }

    @ModifyArg(method = {"renderToFrameBuffer", "renderToMcTexture"}, at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IMinecraftGLWrapper;"
                    + "glBindTexture(I)V", ordinal = 0), index = 0, require = 0, remap = false)
    private int dynamicstage$filterLodColor(int sourceTexture) {
        return StageLodCompositor.filterColorTexture(sourceTexture);
    }

    @Redirect(method = {"renderToFrameBuffer", "renderToMcTexture"}, at = @At(value = "INVOKE",
            target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IMinecraftGLWrapper;"
                    + "disableBlend()V"), require = 0, remap = false)
    private void dynamicstage$configureLodBlend(@Coerce Object wrapper) {
        try {
            Method method = wrapper.getClass().getMethod(
                    StageLodCompositor.needsDhBlending() ? "enableBlend" : "disableBlend");
            method.invoke(wrapper);
            if (StageLodCompositor.needsDhBlending()) {
                GL14C.glBlendFuncSeparate(GL14C.GL_SRC_ALPHA, GL14C.GL_ONE_MINUS_SRC_ALPHA,
                        GL14C.GL_ONE, GL14C.GL_ONE_MINUS_SRC_ALPHA);
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not configure DH stage backdrop blending", e);
        }
    }
}
