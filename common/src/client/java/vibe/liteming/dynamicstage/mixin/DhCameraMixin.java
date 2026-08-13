package vibe.liteming.dynamicstage.mixin;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import vibe.liteming.dynamicstage.client.lod.DhVirtualCamera;
import vibe.liteming.dynamicstage.client.lod.DhMixinMarkers;

import java.lang.reflect.Constructor;

@Pseudo
@Mixin(targets = "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftRenderWrapper_forge",
        remap = false)
public abstract class DhCameraMixin implements DhMixinMarkers.Camera {

    private static volatile Constructor<?> dynamicstage$vec3dConstructor;
    private static volatile Constructor<?> dynamicstage$vec3fConstructor;

    @Inject(method = "getCameraExactPosition", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void dynamicstage$virtualCamera(CallbackInfoReturnable<Object> callback) {
        Vec3 position = DhVirtualCamera.position();
        if (position == null) {
            return;
        }
        try {
            Constructor<?> constructor = dynamicstage$vec3dConstructor;
            if (constructor == null) {
                ClassLoader loader = getClass().getClassLoader();
                Class<?> type;
                try {
                    type = Class.forName("com.seibel.distanthorizons.core.util.math.DhVec3d", true, loader);
                } catch (ClassNotFoundException e) {
                    type = Class.forName("com.seibel.distanthorizons.core.util.math.Vec3d", true, loader);
                }
                constructor = type.getConstructor(double.class, double.class, double.class);
                dynamicstage$vec3dConstructor = constructor;
            }
            callback.setReturnValue(constructor.newInstance(position.x, position.y, position.z));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not construct a DH virtual camera position", e);
        }
    }

    @Inject(method = "getLookAtVector", at = @At("HEAD"), cancellable = true, require = 1, remap = false)
    private void dynamicstage$virtualLookVector(CallbackInfoReturnable<Object> callback) {
        Vec3 look = DhVirtualCamera.lookVector();
        if (look == null) {
            return;
        }
        try {
            Constructor<?> constructor = dynamicstage$vec3fConstructor;
            if (constructor == null) {
                ClassLoader loader = getClass().getClassLoader();
                Class<?> type;
                try {
                    type = Class.forName("com.seibel.distanthorizons.core.util.math.DhVec3f", true, loader);
                } catch (ClassNotFoundException e) {
                    type = Class.forName("com.seibel.distanthorizons.core.util.math.Vec3f", true, loader);
                }
                constructor = type.getConstructor(float.class, float.class, float.class);
                dynamicstage$vec3fConstructor = constructor;
            }
            callback.setReturnValue(constructor.newInstance(
                    (float) look.x, (float) look.y, (float) look.z));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not construct a DH virtual look vector", e);
        }
    }
}
