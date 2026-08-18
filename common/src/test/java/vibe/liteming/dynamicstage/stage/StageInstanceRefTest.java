package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import vibe.liteming.dynamicstage.template.StageTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageInstanceRefTest {
    @Test
    void usesOneBasedSlotAndStageName() {
        StageInstance instance = instance(2, "gr1");

        assertEquals("3(gr1)", StageInstanceRef.label(instance));
        assertTrue(StageInstanceRef.matches(instance, "3(gr1)"));
        assertTrue(StageInstanceRef.matches(instance, instance.instanceId().toString()));
        assertFalse(StageInstanceRef.matches(instance, "2(gr1)"));
    }

    private static StageInstance instance(int slot, String stageId) {
        return new StageInstance(UUID.randomUUID(), stageId,
                new ResourceLocation("dynamicstage", "none"), BlockPos.ZERO, slot, 4,
                StageBoundary.defaults(), StageClientScene.defaults(0L, 0L), false,
                StageTemplate.InteractionPolicy.ADVENTURE, "", 0, 0L, -1L);
    }
}
