package vibe.liteming.dynamicstage.mixin;

import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vibe.liteming.dynamicstage.client.lod.StageBackdropRuntime;
import vibe.liteming.dynamicstage.client.lod.StageLodCamera;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Replaces only DH's copied render matrices, leaving vanilla chunks untouched. */
@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.core.api.internal.ClientApi", remap = false)
public abstract class DhViewMixin {
    private static volatile Field dynamicstage$renderStateField;
    private static volatile Field dynamicstage$modelViewField;
    private static volatile Field dynamicstage$projectionField;
    private static volatile Method dynamicstage$toJoml;
    private static volatile Method dynamicstage$setJoml;

    @Inject(method = {"renderLods", "renderDeferredLodsForShaders"}, at = @At("HEAD"),
            require = 0, remap = false)
    private void dynamicstage$flightView(CallbackInfo callback) {
        StageLodCamera.Snapshot camera = StageLodCamera.snapshot();
        if (!StageBackdropRuntime.isDhMounted() || camera == null || camera.flight() == null) {
            return;
        }
        try {
            resolve(getClass().getClassLoader());
            Object state = dynamicstage$renderStateField.get(null);
            Object modelView = dynamicstage$modelViewField.get(state);
            Object projection = dynamicstage$projectionField.get(state);
            Matrix4f viewMatrix = (Matrix4f) dynamicstage$toJoml.invoke(modelView);
            Matrix4f projectionMatrix = (Matrix4f) dynamicstage$toJoml.invoke(projection);
            dynamicstage$setJoml.invoke(modelView,
                    StageLodCamera.modelView(viewMatrix, camera.flight()));
            dynamicstage$setJoml.invoke(projection,
                    StageLodCamera.projection(projectionMatrix, camera.flight()));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not apply the stage flight to DH's render state", e);
        }
    }

    private static void resolve(ClassLoader loader) throws ReflectiveOperationException {
        if (dynamicstage$renderStateField != null) {
            return;
        }
        Class<?> clientApi = Class.forName(
                "com.seibel.distanthorizons.core.api.internal.ClientApi", true, loader);
        Class<?> renderState = Class.forName(
                "com.seibel.distanthorizons.core.api.internal.rendering.DhRenderState", true, loader);
        Class<?> matrix = Class.forName(
                "com.seibel.distanthorizons.core.util.math.DhMat4f", true, loader);
        dynamicstage$renderStateField = clientApi.getField("RENDER_STATE");
        dynamicstage$modelViewField = renderState.getField("mcModelViewMatrix");
        dynamicstage$projectionField = renderState.getField("mcProjectionMatrix");
        dynamicstage$toJoml = matrix.getMethod("createJomlMatrix");
        dynamicstage$setJoml = matrix.getMethod("set", org.joml.Matrix4fc.class);
    }
}
