package vibe.liteming.dynamicstage.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
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

@Mixin(LevelRenderer.class)
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

    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void dynamicstage$selectStageSky(PoseStack poseStack, Matrix4f projection, float partialTick,
                                              Camera camera, boolean foggy, Runnable setupFog,
                                              CallbackInfo callback) {
        if (StageSkySettings.mode() == StageSkySettings.Mode.OFF
                && StageWorlds.isStageLevel(Minecraft.getInstance().level)) {
            callback.cancel();
        }
    }

    private static StageFlightPose dynamicstage$currentFlight() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!StageWorlds.isStageLevel(minecraft.level)) {
            return null;
        }
        return StageFlightController.currentPose(minecraft.getFrameTime());
    }
}
