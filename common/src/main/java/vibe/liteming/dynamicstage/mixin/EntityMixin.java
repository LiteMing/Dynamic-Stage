package vibe.liteming.dynamicstage.mixin;

import com.google.common.collect.ImmutableList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;
import org.jetbrains.annotations.Nullable;
import vibe.liteming.dynamicstage.stage.StageBoundaryCollisions;

import java.util.List;

@Mixin(value = Entity.class, priority = 1400)
public abstract class EntityMixin {
    @Inject(
            method = "collideBoundingBox",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getWorldBorder()Lnet/minecraft/world/level/border/WorldBorder;"),
            locals = LocalCapture.CAPTURE_FAILHARD,
            require = 0
    )
    private static void dynamicstage$collectBoundaryCollision(
            @Nullable Entity entity,
            Vec3 movement,
            AABB collisionBox,
            Level level,
            List<VoxelShape> potentialHits,
            CallbackInfoReturnable<Vec3> callback,
            ImmutableList.Builder<VoxelShape> builder) {
        if (entity != null) {
            StageBoundaryCollisions.collect(entity, collisionBox.expandTowards(movement), builder::add);
        }
    }
}
