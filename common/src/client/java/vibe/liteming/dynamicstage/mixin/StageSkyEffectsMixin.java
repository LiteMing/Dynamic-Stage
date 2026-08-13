package vibe.liteming.dynamicstage.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vibe.liteming.dynamicstage.client.StageSkySettings;
import vibe.liteming.dynamicstage.world.StageWorlds;

@Mixin(ClientLevel.class)
public abstract class StageSkyEffectsMixin {
    private static final DimensionSpecialEffects OVERWORLD = new DimensionSpecialEffects.OverworldEffects();
    private static final DimensionSpecialEffects END = new DimensionSpecialEffects.EndEffects();

    @Inject(method = "effects", at = @At("HEAD"), cancellable = true)
    private void dynamicstage$selectStageSkyEffect(CallbackInfoReturnable<DimensionSpecialEffects> callback) {
        if (!StageWorlds.isStageLevel((ClientLevel) (Object) this)) {
            return;
        }
        if (StageSkySettings.mode() == StageSkySettings.Mode.OVERWORLD) {
            callback.setReturnValue(OVERWORLD);
        } else if (StageSkySettings.mode() == StageSkySettings.Mode.END) {
            callback.setReturnValue(END);
        }
    }
}
