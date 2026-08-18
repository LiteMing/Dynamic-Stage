package vibe.liteming.dynamicstage.mixin;

import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PersistentEntitySectionManager.class)
public interface PersistentEntitySectionManagerInvoker {
    @Invoker("processPendingLoads")
    void dynamicstage$processPendingLoads();
}
