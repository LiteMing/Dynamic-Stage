package vibe.liteming.dynamicstage.mixin;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vibe.liteming.dynamicstage.client.StageTransitionManager;

@Mixin(ReceivingLevelScreen.class)
public abstract class ReceivingLevelScreenMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void dynamicstage$hideVanillaTerrainScreen(GuiGraphics graphics, int mouseX, int mouseY,
                                                        float partialTick, CallbackInfo callback) {
        if (StageTransitionManager.active()) {
            callback.cancel();
        }
    }
}
