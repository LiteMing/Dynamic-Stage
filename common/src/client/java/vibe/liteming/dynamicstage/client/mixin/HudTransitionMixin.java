package vibe.liteming.dynamicstage.mixin;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vibe.liteming.dynamicstage.client.StageTransitionManager;

/** Fallback overlay hook for clients where the platform HUD event is unavailable during loading. */
@Mixin(Gui.class)
public abstract class HudTransitionMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void dynamicstage$renderTransition(GuiGraphics graphics, float partialTick, CallbackInfo callback) {
        StageTransitionManager.render(graphics, partialTick);
    }
}
