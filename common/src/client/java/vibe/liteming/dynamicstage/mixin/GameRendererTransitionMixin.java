package vibe.liteming.dynamicstage.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vibe.liteming.dynamicstage.client.StageTransitionManager;

@Mixin(GameRenderer.class)
public abstract class GameRendererTransitionMixin {
    @Inject(method = "render", at = @At("TAIL"), require = 1)
    private void dynamicstage$renderTransition(float partialTick, long nanoTime, boolean renderLevel,
                                                CallbackInfo callback) {
        if (!StageTransitionManager.active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = new GuiGraphics(minecraft, minecraft.renderBuffers().bufferSource());
        StageTransitionManager.render(graphics, partialTick);
        graphics.flush();
    }
}
