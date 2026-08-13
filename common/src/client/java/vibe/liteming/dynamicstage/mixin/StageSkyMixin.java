package vibe.liteming.dynamicstage.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vibe.liteming.dynamicstage.client.StageSkySettings;
import vibe.liteming.dynamicstage.client.flight.StageFlightController;
import vibe.liteming.dynamicstage.client.flight.StageFlightPose;
import vibe.liteming.dynamicstage.client.lod.StageLodCamera;
import vibe.liteming.dynamicstage.world.StageWorlds;

@Mixin(value = LevelRenderer.class, priority = 900)
public abstract class StageSkyMixin {
    @ModifyVariable(method = "renderSky", at = @At("HEAD"), argsOnly = true, require = 1)
    private PoseStack dynamicstage$flightSkyView(PoseStack original) {
        StageFlightPose flight = dynamicstage$currentFlight();
        if (flight == null) {
            return original;
        }
        PoseStack transformed = new PoseStack();
        transformed.last().pose().set(StageLodCamera.modelView(original.last().pose(), flight));
        transformed.last().normal().set(original.last().normal());
        return transformed;
    }

    @ModifyVariable(method = "renderSky", at = @At("HEAD"), argsOnly = true, require = 1)
    private Matrix4f dynamicstage$flightSkyProjection(Matrix4f original) {
        return StageLodCamera.projection(original, dynamicstage$currentFlight());
    }

    @ModifyVariable(method = "renderClouds", at = @At("HEAD"), argsOnly = true, require = 1)
    private PoseStack dynamicstage$flightCloudView(PoseStack original) {
        StageLodCamera.Snapshot backdrop = StageLodCamera.snapshot();
        if (backdrop == null || backdrop.flight() == null) {
            return original;
        }
        PoseStack transformed = new PoseStack();
        transformed.last().pose().set(StageLodCamera.modelView(
                original.last().pose(), backdrop.flight()));
        transformed.last().normal().set(original.last().normal());
        return transformed;
    }

    @ModifyVariable(method = "renderClouds", at = @At("HEAD"), argsOnly = true, require = 1)
    private Matrix4f dynamicstage$flightCloudProjection(Matrix4f original) {
        StageLodCamera.Snapshot backdrop = StageLodCamera.snapshot();
        return StageLodCamera.projection(original, backdrop == null ? null : backdrop.flight());
    }

    @ModifyVariable(method = "renderClouds", at = @At("HEAD"), argsOnly = true,
            ordinal = 0, require = 1)
    private double dynamicstage$cloudX(double original) {
        Vec3 position = dynamicstage$backdropPosition();
        return position == null ? original : position.x;
    }

    @ModifyVariable(method = "renderClouds", at = @At("HEAD"), argsOnly = true,
            ordinal = 1, require = 1)
    private double dynamicstage$cloudY(double original) {
        Vec3 position = dynamicstage$backdropPosition();
        return position == null ? original : position.y;
    }

    @ModifyVariable(method = "renderClouds", at = @At("HEAD"), argsOnly = true,
            ordinal = 2, require = 1)
    private double dynamicstage$cloudZ(double original) {
        Vec3 position = dynamicstage$backdropPosition();
        return position == null ? original : position.z;
    }

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void dynamicstage$selectStageSky(PoseStack poseStack, Matrix4f projection, float partialTick,
                                              Camera camera, boolean foggy, Runnable setupFog,
                                              CallbackInfo callback) {
        if (dynamicstage$skyDisabled()) {
            callback.cancel();
        }
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void dynamicstage$selectStageClouds(PoseStack poseStack, Matrix4f projection, float partialTick,
                                                 double cameraX, double cameraY, double cameraZ,
                                                 CallbackInfo callback) {
        if (dynamicstage$skyDisabled()) {
            callback.cancel();
        }
    }

    private static Vec3 dynamicstage$backdropPosition() {
        StageLodCamera.Snapshot backdrop = StageLodCamera.snapshot();
        return backdrop == null ? null : backdrop.position();
    }

    private static boolean dynamicstage$skyDisabled() {
        return StageSkySettings.mode() == StageSkySettings.Mode.OFF
                && StageWorlds.isStageLevel(Minecraft.getInstance().level);
    }

    private static StageFlightPose dynamicstage$currentFlight() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!StageWorlds.isStageLevel(minecraft.level)) {
            return null;
        }
        return StageFlightController.currentPose(minecraft.getFrameTime());
    }
}
