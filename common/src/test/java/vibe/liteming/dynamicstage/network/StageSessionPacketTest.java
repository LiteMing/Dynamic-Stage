package vibe.liteming.dynamicstage.network;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StageSessionPacketTest {
    @Test
    void preservesClientSceneAndTrailingFlightState() {
        StageSessionPacket packet = new StageSessionPacket(true, UUID.randomUUID(), "boss",
                new ResourceLocation("stages", "city"), new BlockPos(20, 90, -30),
                new BlockPos(4096, 80, 0), 4, new StageBoundary(80, 60, 20, 0x12ABEF),
                new StageClientScene(false, 0.5F, 0.075F, 0.035F, false, 8.0F,
                        StageClientScene.Transition.BLUR, 30, 410L,
                        StageClientScene.TimeMode.CYCLE, 18_000L, 400L, 1_200L,
                        StageClientScene.SkyMode.OFF),
                "ab".repeat(32), 512, 8_000L);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        StageSessionPacket.encode(packet, buffer);

        assertEquals(packet, StageSessionPacket.decode(buffer));
        buffer.release();
    }
}
