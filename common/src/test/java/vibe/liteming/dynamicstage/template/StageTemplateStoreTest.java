package vibe.liteming.dynamicstage.template;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
                flight(), arenaSnapshot(), "scarlet");

        StageTemplateStore.save(temporaryDirectory, template);

        assertEquals(template, StageTemplateStore.load(temporaryDirectory, "boss_1"));
        assertNull(StageTemplateStore.load(temporaryDirectory, "missing"));
    }

    @Test
    void migratesLegacyConfigTemplatesIntoPortableResourceDirectory() throws Exception {
        Path legacy = temporaryDirectory.resolve("config/dynamicstage/templates");
        Path portable = temporaryDirectory.resolve("dynamicstage/templates");
        StageTemplate template = new StageTemplate("gr1", new ResourceLocation("minecraft", "gr"),
                BlockPos.ZERO, StageBoundary.defaults(), StageClientScene.defaults(0L, 0L), 1,
                StageTemplate.InstanceMode.PARALLEL, StageTemplate.ResetPolicy.ON_CREATE,
                new byte[0], new CompoundTag());
        StageTemplateStore.save(legacy, template);

        assertEquals(1, StageTemplateStore.migrate(legacy, portable));
        assertNotNull(StageTemplateStore.load(portable, "gr1"));
        assertEquals(0, StageTemplateStore.migrate(legacy, portable));
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
    void editorSummaryClearsArenaWhenBoundarySizeChanges() {
        CompoundTag arena = arenaSnapshot();
        StageTemplate existing = new StageTemplate("stage", new ResourceLocation("pack", "old"), BlockPos.ZERO,
                StageBoundary.defaults(), StageClientScene.defaults(0L, 0L), 1,
                StageTemplate.InstanceMode.PARALLEL, StageTemplate.ResetPolicy.ON_CREATE,
                flight(), arena, "old-flight");
        StageTemplateSummary summary = new StageTemplateSummary("stage", new ResourceLocation("pack", "new"),
                new BlockPos(10, 20, 30), new StageBoundary(40, 44, 16, 0x445566),
                StageClientScene.defaults(6000L, 20L), 4, StageTemplate.InstanceMode.SHARED,
                StageTemplate.ResetPolicy.MANUAL, "scarlet");

        StageTemplate edited = summary.applyTo(existing);

        assertEquals(new CompoundTag(), edited.arenaSnapshot());
        assertEquals(new ResourceLocation("pack", "new"), edited.lodPackId());
        assertEquals(4, edited.capacity());
        assertEquals("old-flight", edited.flightName());
    }

    @Test
    void editorSummaryPreservesArenaWhenBoundaryIsUnchanged() {
        CompoundTag arena = arenaSnapshot();
        StageBoundary boundary = new StageBoundary(40, 44, 16, 0x112233);
        StageTemplate existing = new StageTemplate("stage", new ResourceLocation("pack", "old"), BlockPos.ZERO,
                boundary, StageClientScene.defaults(0L, 0L), 1,
                StageTemplate.InstanceMode.PARALLEL, StageTemplate.ResetPolicy.ON_CREATE,
                flight(), arena, "old-flight");
        StageTemplateSummary summary = new StageTemplateSummary("stage", new ResourceLocation("pack", "new"),
                BlockPos.ZERO, boundary.withColor(0x445566), StageClientScene.defaults(0L, 0L), 1,
                StageTemplate.InstanceMode.PARALLEL, StageTemplate.ResetPolicy.ON_CREATE, "old-flight");

        assertEquals(arena, summary.applyTo(existing).arenaSnapshot());
    }

    private static CompoundTag arenaSnapshot() {
        CompoundTag snapshot = new CompoundTag();
        snapshot.putInt("test", 1);
        return snapshot;
    }

    private static byte[] flight() {
        return ("{\"duration\":1000,\"loop\":0,\"mode\":\"outside\",\"inter\":\"linear\"," +
                "\"smooth_start\":false,\"pitch_mode\":0,\"d_timing\":false,\"points\":[" +
                "{\"x\":0,\"y\":64,\"z\":0,\"rotationYaw\":0,\"rotationPitch\":0,\"roll\":0,\"zoom\":70}," +
                "{\"x\":1,\"y\":64,\"z\":0,\"rotationYaw\":0,\"rotationPitch\":0,\"roll\":0,\"zoom\":70}]}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
