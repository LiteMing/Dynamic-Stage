package vibe.liteming.dynamicstage.mixin;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vibe.liteming.dynamicstage.client.lod.DhVirtualCamera;

import java.lang.reflect.Constructor;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper_forge",
        remap = false)
public abstract class DhPlayerPositionMixin {

    @Inject(method = "getPlayerBlockPos", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void dynamicstage$virtualPlayerBlock(CallbackInfoReturnable<Object> callback) {
        Vec3 position = DhVirtualCamera.lodSelectionPosition();
        if (position != null) {
            callback.setReturnValue(dynamicstage$new("com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos",
                    Mth.floor(position.x), Mth.floor(position.y), Mth.floor(position.z)));
        }
    }

    @Inject(method = "getPlayerChunkPos", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void dynamicstage$virtualPlayerChunk(CallbackInfoReturnable<Object> callback) {
        Vec3 position = DhVirtualCamera.lodSelectionPosition();
        if (position != null) {
            callback.setReturnValue(dynamicstage$new("com.seibel.distanthorizons.core.pos.DhChunkPos",
                    Mth.floor(position.x) >> 4, Mth.floor(position.z) >> 4));
        }
    }

    private Object dynamicstage$new(String className, int... values) {
        try {
            Class<?> type = Class.forName(className, true, getClass().getClassLoader());
            Class<?>[] parameters = new Class<?>[values.length];
            java.util.Arrays.fill(parameters, int.class);
            Constructor<?> constructor = type.getConstructor(parameters);
            Object[] arguments = java.util.Arrays.stream(values).boxed().toArray();
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not construct a DH virtual player position", e);
        }
    }
}
