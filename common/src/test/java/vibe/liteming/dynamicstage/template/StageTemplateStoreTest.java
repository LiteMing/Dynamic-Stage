package vibe.liteming.dynamicstage.template;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StageTemplateStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsPortableTemplateWithoutAbsolutePaths() throws Exception {
        StageTemplate template = new StageTemplate("boss_1", new ResourceLocation("pack", "city"),
                new BlockPos(-2089, 128, 7627), new StageBoundary(80, 60, 24, 0x12ABEF),
                new StageClientScene(true, 0.35F, 0.05F, true, 2.0F, StageClientScene.Transition.FADE,
                        20, 200L, StageClientScene.TimeMode.CYCLE, 18_000L, 100L, 1_200L,
                        StageClientScene.SkyMode.OVERWORLD),
                4, StageTemplate.InstanceMode.SHARED, StageTemplate.ResetPolicy.ON_CREATE,
                new byte[0], arenaSnapshot());

        StageTemplateStore.save(temporaryDirectory, template);

        assertEquals(template, StageTemplateStore.load(temporaryDirectory, "boss_1"));
        assertNull(StageTemplateStore.load(temporaryDirectory, "missing"));
    }

    @Test
    void rebasesClientEpochsForEachNewWorldInstance() {
        StageClientScene scene = new StageClientScene(true, 0.5F, 0.125F, true, 0.0F,
                StageClientScene.Transition.INSTANT, 0, 50L,
                StageClientScene.TimeMode.FOLLOW, 6000L, 50L, 0L, StageClientScene.SkyMode.OVERWORLD);
        StageTemplate template = new StageTemplate("stage", new ResourceLocation("pack", "lod"), BlockPos.ZERO,
                StageBoundary.defaults(), scene, 1, StageTemplate.InstanceMode.PARALLEL,
                StageTemplate.ResetPolicy.MANUAL, new byte[0], new CompoundTag());

        StageClientScene rebased = template.sceneForNewInstance(12_000L, 900L);

        assertEquals(12_000L, rebased.timeBaseDayTime());
        assertEquals(900L, rebased.timeBaseGameTime());
        assertEquals(900L, rebased.lodTransitionStartGameTime());
        assertEquals(0.125F, rebased.dhNearFadeScale());
    }

    @Test
    void editorSummaryPreservesServerOwnedAssets() {
        CompoundTag arena = arenaSnapshot();
        StageTemplate existing = new StageTemplate("stage", new ResourceLocation("pack", "old"), BlockPos.ZERO,
                StageBoundary.defaults(), StageClientScene.defaults(0L, 0L), 1,
                StageTemplate.InstanceMode.PARALLEL, StageTemplate.ResetPolicy.ON_CREATE,
                new byte[0], arena);
        StageTemplateSummary summary = new StageTemplateSummary("stage", new ResourceLocation("pack", "new"),
                new BlockPos(10, 20, 30), new StageBoundary(40, 44, 16, 0x445566),
                StageClientScene.defaults(6000L, 20L), 4, StageTemplate.InstanceMode.SHARED,
                StageTemplate.ResetPolicy.MANUAL);

        StageTemplate edited = summary.applyTo(existing);

        assertEquals(arena, edited.arenaSnapshot());
        assertEquals(new ResourceLocation("pack", "new"), edited.lodPackId());
        assertEquals(4, edited.capacity());
    }

    private static CompoundTag arenaSnapshot() {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putInt("test", 1);
        return snapshot;
    }
}
