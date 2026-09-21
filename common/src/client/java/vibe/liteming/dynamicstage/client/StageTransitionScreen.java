package vibe.liteming.dynamicstage.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Input barrier only. The compositor renders last, even during a forced loading tick. */
public final class StageTransitionScreen extends Screen {
    public StageTransitionScreen() { super(Component.empty()); }
    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }
    @Override public void render(GuiGraphics graphics, int x, int y, float partialTick) { }
}
