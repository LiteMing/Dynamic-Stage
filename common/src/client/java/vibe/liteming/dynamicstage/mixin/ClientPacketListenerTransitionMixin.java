package vibe.liteming.dynamicstage.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import vibe.liteming.dynamicstage.client.StageTransitionManager;

/** Covers the concrete respawn call site that installs the vanilla terrain screen. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTransitionMixin {
    @Redirect(method = "handleRespawn",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void dynamicstage$redirectRespawnScreen(Minecraft minecraft, Screen screen) {
        if (!(StageTransitionManager.active() && screen instanceof ReceivingLevelScreen)) {
            minecraft.setScreen(screen);
        }
    }
}
