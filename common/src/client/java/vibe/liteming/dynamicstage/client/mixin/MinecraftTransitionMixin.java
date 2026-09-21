package vibe.liteming.dynamicstage.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vibe.liteming.dynamicstage.client.StageTransitionManager;

/** Keeps vanilla's terrain download screen from replacing the Dynamic Stage compositor. */
@Mixin(Minecraft.class)
public abstract class MinecraftTransitionMixin {
    @Inject(method = "setScreen", at = @At("HEAD"), cancellable = true)
    private void dynamicstage$hideVanillaTransition(Screen screen, CallbackInfo callback) {
        if (StageTransitionManager.active() && screen instanceof ReceivingLevelScreen) {
            callback.cancel();
        }
    }
}
