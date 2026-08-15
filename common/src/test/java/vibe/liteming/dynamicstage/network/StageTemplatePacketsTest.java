package vibe.liteming.dynamicstage.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;
import vibe.liteming.dynamicstage.template.StageTemplate;
import vibe.liteming.dynamicstage.template.StageTemplateSummary;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StageTemplatePacketsTest {
    @Test
    void roundTripsEditableTemplateSummaries() {
        StageTemplateSummary summary = new StageTemplateSummary("boss_1",
                new ResourceLocation("stages", "city"), new BlockPos(-20, 100, 30),
                new StageBoundary(80, 60, 24, 0x12ABEF),
                new StageClientScene(true, 0.4F, 0.2F, 0.04F, false, 3.0F, StageClientScene.Transition.BLUR,
                        40, 500L, StageClientScene.TimeMode.CYCLE, 18_000L, 500L, 1_200L,
                        StageClientScene.SkyMode.END), 4, StageTemplate.InstanceMode.SHARED,
                StageTemplate.ResetPolicy.ON_CREATE);
        FriendlyByteBuf listBuffer = new FriendlyByteBuf(Unpooled.buffer());
        FriendlyByteBuf editBuffer = new FriendlyByteBuf(Unpooled.buffer());

        StageTemplatePackets.ListPacket list = new StageTemplatePackets.ListPacket(List.of(summary));
        StageTemplatePackets.encodeList(list, listBuffer);
        StageTemplatePackets.EditPacket edit = new StageTemplatePackets.EditPacket(
                StageTemplatePackets.Action.USE_CONFIGURED_FLIGHT, summary);
        StageTemplatePackets.encodeEdit(edit, editBuffer);

        assertEquals(list, StageTemplatePackets.decodeList(listBuffer));
        assertEquals(edit, StageTemplatePackets.decodeEdit(editBuffer));
        listBuffer.release();
        editBuffer.release();
    }
}
