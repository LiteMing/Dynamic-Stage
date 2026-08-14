package vibe.liteming.dynamicstage.mixin;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL20C;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vibe.liteming.dynamicstage.client.lod.DhVirtualCamera;
import vibe.liteming.dynamicstage.client.lod.StageLodCamera;

/** Applies the stage camera to the DH shader program supplied by Oculus. */
@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.dh.IrisLodRenderProgram", remap = false)
public abstract class OculusDhShaderMixin {
    private static final String FILL_UNIFORM_DATA =
            "fillUniformData(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;IF)V";
    @Unique
    private static final int dynamicstage$UNRESOLVED_UNIFORM = Integer.MIN_VALUE;

    @Shadow
    @Final
    private int id;

    @Unique
    private int dynamicstage$farUniform = dynamicstage$UNRESOLVED_UNIFORM;

    @ModifyVariable(method = FILL_UNIFORM_DATA, at = @At("HEAD"),
            argsOnly = true, ordinal = 0, require = 1, remap = false)
    private Matrix4fc dynamicstage$flightProjection(Matrix4fc original) {
        StageLodCamera.Snapshot camera = StageLodCamera.snapshot();
        if (camera == null || camera.flight() == null) {
            return original;
        }
        return StageLodCamera.projection(new Matrix4f(original), camera.flight());
    }

    @ModifyVariable(method = FILL_UNIFORM_DATA, at = @At("HEAD"),
            argsOnly = true, ordinal = 1, require = 1, remap = false)
    private Matrix4fc dynamicstage$flightModelView(Matrix4fc original) {
        StageLodCamera.Snapshot camera = StageLodCamera.snapshot();
        if (camera == null || camera.flight() == null) {
            return original;
        }
        return StageLodCamera.modelView(new Matrix4f(original), camera.flight());
    }

    @ModifyArg(method = FILL_UNIFORM_DATA,
            at = @At(value = "INVOKE",
                    target = "Lnet/irisshaders/iris/compat/dh/IrisLodRenderProgram;setUniform(IF)V",
                    ordinal = 2),
            index = 1,
            require = 1,
            remap = false)
    private float dynamicstage$renderLodUpToStageCamera(float original) {
        return DhVirtualCamera.position() == null ? original : 0.01F;
    }

    @Inject(method = FILL_UNIFORM_DATA, at = @At("TAIL"), require = 1, remap = false)
    private void dynamicstage$shrinkShaderPackNearFade(Matrix4fc projection, Matrix4fc modelView,
                                                       int worldYOffset, float partialTick,
                                                       CallbackInfo callback) {
        if (DhVirtualCamera.position() == null) {
            return;
        }
        int location = dynamicstage$farUniform;
        if (location == dynamicstage$UNRESOLVED_UNIFORM) {
            location = GL20C.glGetUniformLocation(id, "far");
            dynamicstage$farUniform = location;
        }
        if (location < 0) {
            return;
        }
        float original = GL20C.glGetUniformf(id, location);
        if (Float.isFinite(original) && original > 0.0F) {
            GL20C.glUniform1f(location, Math.max(0.01F, original * 0.1F));
        }
    }
}
