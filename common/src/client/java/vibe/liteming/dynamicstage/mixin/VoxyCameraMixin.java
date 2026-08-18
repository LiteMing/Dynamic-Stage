package vibe.liteming.dynamicstage.mixin;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.joml.Matrix4f;
import vibe.liteming.dynamicstage.client.lod.StageBackdropRuntime;
import vibe.liteming.dynamicstage.client.lod.StageLodCamera;
import vibe.liteming.dynamicstage.client.lod.VoxyBackdropRuntime;
import vibe.liteming.dynamicstage.client.lod.VoxyVirtualCamera;

@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.VoxyRenderSystem", remap = false)
public abstract class VoxyCameraMixin {
    @Inject(method = "setupViewport", at = @At("HEAD"), require = 0, remap = false)
    private void dynamicstage$configureStageRendering(CallbackInfoReturnable<Object> callback) {
        VoxyBackdropRuntime.syncStageRendering();
    }

    @ModifyVariable(method = "setupViewport", at = @At("HEAD"), argsOnly = true,
            ordinal = 0, require = 0, remap = false)
    private double dynamicstage$cameraX(double original) {
        Vec3 position = VoxyVirtualCamera.position();
        return position == null ? original : position.x;
    }

    @ModifyVariable(method = "setupViewport", at = @At("HEAD"), argsOnly = true,
            ordinal = 1, require = 0, remap = false)
    private double dynamicstage$cameraY(double original) {
        Vec3 position = VoxyVirtualCamera.position();
        return position == null ? original : position.y;
    }

    @ModifyVariable(method = "setupViewport", at = @At("HEAD"), argsOnly = true,
            ordinal = 2, require = 0, remap = false)
    private double dynamicstage$cameraZ(double original) {
        Vec3 position = VoxyVirtualCamera.position();
        return position == null ? original : position.z;
    }

    @Inject(method = "setupViewport", at = @At("RETURN"), require = 0, remap = false)
    private void dynamicstage$flightView(CallbackInfoReturnable<Object> callback) {
        Object viewport = callback.getReturnValue();
        StageLodCamera.Snapshot camera = StageLodCamera.snapshot();
        if (!StageBackdropRuntime.isVoxyMounted() || viewport == null || camera == null
                || camera.flight() == null) {
            return;
        }
        try {
            Class<?> type = viewport.getClass();
            java.lang.reflect.Field vanillaProjection = type.getField("vanillaProjection");
            java.lang.reflect.Field projection = type.getField("projection");
            java.lang.reflect.Field modelView = type.getField("modelView");
            Matrix4f vanilla = (Matrix4f) vanillaProjection.get(viewport);
            Matrix4f lodProjection = (Matrix4f) projection.get(viewport);
            Matrix4f view = (Matrix4f) modelView.get(viewport);
            vanilla.set(StageLodCamera.projection(vanilla, camera.flight()));
            lodProjection.set(StageLodCamera.projection(lodProjection, camera.flight()));
            view.set(StageLodCamera.modelView(view, camera.flight()));
            type.getMethod("update").invoke(viewport);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not apply the stage flight to Voxy's viewport", e);
        }
    }
}
