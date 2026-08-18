package vibe.liteming.dynamicstage.template;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;

/** Small editable portion of a template; arena and flight assets never travel in editor packets. */
public record StageTemplateSummary(String id, ResourceLocation lodPackId, BlockPos lodAnchor,
                                   StageBoundary boundary, StageClientScene clientScene, int capacity,
                                   StageTemplate.InstanceMode instanceMode,
                                   StageTemplate.LifecyclePolicy lifecyclePolicy, String flightName) {
    public StageTemplateSummary {
        // Reuse the full template's validation without retaining asset data.
        new StageTemplate(id, lodPackId, lodAnchor, boundary, clientScene, capacity,
                instanceMode, lifecyclePolicy, new byte[0], new CompoundTag());
        if (flightName == null || (!flightName.isEmpty() && !StageTemplate.validFlightName(flightName))) {
            throw new IllegalArgumentException("Invalid template flight name");
        }
    }

    public StageTemplateSummary(String id, ResourceLocation lodPackId, BlockPos lodAnchor,
                                StageBoundary boundary, StageClientScene clientScene, int capacity,
                                StageTemplate.InstanceMode instanceMode,
                                StageTemplate.LifecyclePolicy lifecyclePolicy) {
        this(id, lodPackId, lodAnchor, boundary, clientScene, capacity, instanceMode, lifecyclePolicy, "");
    }

    public static StageTemplateSummary from(StageTemplate template) {
        return new StageTemplateSummary(template.id(), template.lodPackId(), template.lodAnchor(),
                template.boundary(), template.clientScene(), template.capacity(),
                template.instanceMode(), template.lifecyclePolicy(), template.flightName());
    }

    public StageTemplate applyTo(StageTemplate existing) {
        byte[] flight = existing == null ? new byte[0] : existing.flightJson();
        CompoundTag arena = compatibleArena(existing);
        return new StageTemplate(id, lodPackId, lodAnchor, boundary, clientScene, capacity,
                instanceMode, lifecyclePolicy, flight, arena, existing == null ? "" : existing.flightName());
    }

    public StageTemplate applyTo(StageTemplate existing, byte[] flight, String selectedFlightName) {
        CompoundTag arena = compatibleArena(existing);
        return new StageTemplate(id, lodPackId, lodAnchor, boundary, clientScene, capacity,
                instanceMode, lifecyclePolicy, flight == null ? new byte[0] : flight, arena,
                selectedFlightName == null ? "" : selectedFlightName);
    }

    private CompoundTag compatibleArena(StageTemplate existing) {
        return existing == null || !sameBoundarySize(existing.boundary(), boundary)
                ? new CompoundTag() : existing.arenaSnapshot();
    }

    public static boolean sameBoundarySize(StageBoundary first, StageBoundary second) {
        return first != null && second != null && first.width() == second.width()
                && first.depth() == second.depth() && first.height() == second.height();
    }
}
