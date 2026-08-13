package vibe.liteming.dynamicstage.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vibe.liteming.dynamicstage.client.StageSkySettings;
import vibe.liteming.dynamicstage.world.StageWorlds;

@Mixin(LevelRenderer.class)
public abstract class StageSkyMixin {
    @Inject(method = "renderSky", at = @At("HEAD"), cancellable = true)
    private void dynamicstage$selectStageSky(PoseStack poseStack, Matrix4f projection, float partialTick,
                                              Camera camera, boolean foggy, Runnable setupFog,
                                              CallbackInfo callback) {
        if (StageSkySettings.mode() == StageSkySettings.Mode.OFF
                && StageWorlds.isStageLevel(Minecraft.getInstance().level)) {
            callback.cancel();
        }
    }

}
