package vibe.liteming.dynamicstage.template;

import com.google.gson.JsonParser;
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
                new StageClientScene(true, 0.35F, 0.05F, 0.75F, false, true, 2.0F,
                        StageClientScene.Transition.FADE,
                        20, 200L, StageClientScene.TimeMode.CYCLE, 18_000L, 100L, 1_200L,
                        StageClientScene.SkyMode.OVERWORLD),
                4, StageTemplate.InstanceMode.SHARED, StageTemplate.LifecyclePolicy.RELEASE_WHEN_EMPTY,
                StageTemplate.CleanupPolicy.OVERLAY, StageTemplate.InteractionPolicy.ADVENTURE,
                flight(), arenaSnapshot(), "scarlet",
                BlockPos.ZERO, java.util.List.of());

        StageTemplateStore.save(temporaryDirectory, template);

        assertEquals(template, StageTemplateStore.load(temporaryDirectory, "boss_1"));
        assertNull(StageTemplateStore.load(temporaryDirectory, "missing"));
    }

    @Test
    void parsesDataPackTemplateStructuresAndEntryOffset() {
        StageTemplate template = StageDataTemplateStore.parse(JsonParser.parseString("""
                {
                  "id": "cirno",
                  "boundary": {"width": 32, "depth": 72, "height": 17, "color": "48B8FF"},
                  "entry_offset": [0, 8, -35],
                  "capacity": 4,
                  "instance_mode": "shared",
                  "lifecycle": "retain",
                  "cleanup": "overlay",
                  "scene": {"lod_visible": false, "voxy_near_plane": 2.0, "sky": "overworld"},
                  "structures": [
                    {"id": "touhou:level1", "offset": [-16, 0, -32]},
                    {"id": "touhou:platform", "offset": [0, 6, -35]}
                  ]
                }
                """).getAsJsonObject());

        assertEquals("cirno", template.id());
        assertEquals(new ResourceLocation("dynamicstage", "none"), template.lodPackId());
        assertEquals(new BlockPos(0, 8, -35), template.entryOffset());
        assertEquals(4, template.capacity());
        assertEquals(StageTemplate.InstanceMode.SHARED, template.instanceMode());
        assertEquals(StageTemplate.LifecyclePolicy.RETAIN, template.lifecyclePolicy());
        assertEquals(StageTemplate.CleanupPolicy.OVERLAY, template.cleanupPolicy());
        assertEquals(2.0F, template.clientScene().voxyNearPlane());
        assertEquals(2, template.structures().size());
        assertEquals(template, StageTemplate.load(template.save()));
    }

    @Test
    void migratesLegacyConfigTemplatesIntoPortableResourceDirectory() throws Exception {
        Path legacy = temporaryDirectory.resolve("config/dynamicstage/templates");
        Path portable = temporaryDirectory.resolve("dynamicstage/templates");
        StageTemplate template = new StageTemplate("gr1", new ResourceLocation("minecraft", "gr"),
                BlockPos.ZERO, StageBoundary.defaults(), StageClientScene.defaults(0L, 0L), 1,
                StageTemplate.InstanceMode.PARALLEL, StageTemplate.LifecyclePolicy.RELEASE_WHEN_EMPTY,
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
                StageTemplate.LifecyclePolicy.RETAIN, new byte[0], new CompoundTag());

        StageClientScene rebased = template.sceneForNewInstance(12_000L, 900L);

        assertEquals(12_000L, rebased.timeBaseDayTime());
        assertEquals(900L, rebased.timeBaseGameTime());
        assertEquals(900L, rebased.lodTransitionStartGameTime());
        assertEquals(0.125F, rebased.dhNearFadeScale());
        assertEquals(scene.voxyNearPlane(), rebased.voxyNearPlane());
    }

    @Test
    void editorSummaryClearsArenaWhenBoundarySizeChanges() {
        CompoundTag arena = arenaSnapshot();
        StageTemplate existing = new StageTemplate("stage", new ResourceLocation("pack", "old"), BlockPos.ZERO,
                StageBoundary.defaults(), StageClientScene.defaults(0L, 0L), 1,
                StageTemplate.InstanceMode.PARALLEL, StageTemplate.LifecyclePolicy.RELEASE_WHEN_EMPTY,
                flight(), arena, "old-flight");
        StageTemplateSummary summary = new StageTemplateSummary("stage", new ResourceLocation("pack", "new"),
                new BlockPos(10, 20, 30), new StageBoundary(40, 44, 16, 0x445566),
                StageClientScene.defaults(6000L, 20L), 4, StageTemplate.InstanceMode.SHARED,
                StageTemplate.LifecyclePolicy.RETAIN, "scarlet");

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
                StageTemplate.InstanceMode.PARALLEL, StageTemplate.LifecyclePolicy.RELEASE_WHEN_EMPTY,
                flight(), arena, "old-flight");
        StageTemplateSummary summary = new StageTemplateSummary("stage", new ResourceLocation("pack", "new"),
                BlockPos.ZERO, boundary.withColor(0x445566), StageClientScene.defaults(0L, 0L), 1,
                StageTemplate.InstanceMode.PARALLEL, StageTemplate.LifecyclePolicy.RELEASE_WHEN_EMPTY,
                "old-flight");

        assertEquals(arena, summary.applyTo(existing).arenaSnapshot());
    }

    @Test
    void migratesLegacyResetPolicyToInstanceLifecycle() {
        StageTemplate template = new StageTemplate("legacy", new ResourceLocation("pack", "lod"),
                BlockPos.ZERO, StageBoundary.defaults(), StageClientScene.defaults(0L, 0L), 1,
                StageTemplate.InstanceMode.PARALLEL, StageTemplate.LifecyclePolicy.RELEASE_WHEN_EMPTY,
                new byte[0], new CompoundTag());
        CompoundTag retained = template.save();
        retained.putInt("Format", 2);
        retained.remove("CleanupPolicy");
        retained.remove("LifecyclePolicy");
        retained.putString("ResetPolicy", "MANUAL");
        CompoundTag released = retained.copy();
        released.putString("ResetPolicy", "ON_CREATE");

        assertEquals(StageTemplate.LifecyclePolicy.RETAIN, StageTemplate.load(retained).lifecyclePolicy());
        assertEquals(StageTemplate.CleanupPolicy.FULL, StageTemplate.load(retained).cleanupPolicy());
        assertEquals(StageTemplate.LifecyclePolicy.RELEASE_WHEN_EMPTY,
                StageTemplate.load(released).lifecyclePolicy());
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
