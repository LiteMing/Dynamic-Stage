package vibe.liteming.dynamicstage.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import vibe.liteming.dynamicstage.client.StageTransitionManager;

@Mixin(Minecraft.class)
public abstract class MinecraftTransitionMixin {
    @ModifyVariable(method = "setScreen", at = @At("HEAD"), argsOnly = true, require = 1)
    private Screen dynamicstage$replaceLoadingScreen(Screen screen) {
        return StageTransitionManager.replaceLoadingScreen(screen);
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/Window;updateDisplay()V", shift = At.Shift.AFTER), require = 1)
    private void dynamicstage$framePresented(boolean tick, CallbackInfo callback) {
        StageTransitionManager.framePresented();
    }
}
