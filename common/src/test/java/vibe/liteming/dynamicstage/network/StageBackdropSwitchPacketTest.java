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

class StageBackdropSwitchPacketTest {
    @Test
    void preservesTargetSessionAndTransition() {
        StageSessionPacket session = new StageSessionPacket(true, UUID.randomUUID(), "boss",
                new ResourceLocation("stages", "night_city"), new BlockPos(12, 96, -40),
                new BlockPos(4096, 80, 0), 4, StageBoundary.defaults(),
                StageClientScene.defaults(6_000L, 100L), "", 0, 0L);
        StageBackdropSwitchPacket packet = new StageBackdropSwitchPacket(
                session, StageClientScene.Transition.BLUR, 60);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        StageBackdropSwitchPacket.encode(packet, buffer);

        assertEquals(packet, StageBackdropSwitchPacket.decode(buffer));
        buffer.release();
    }

    @Test
    void preservesClientResult() {
        StageBackdropSwitchResultPacket packet = new StageBackdropSwitchResultPacket(
                UUID.randomUUID(), new ResourceLocation("stages", "night_city"), false, "mount failed");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());

        StageBackdropSwitchResultPacket.encode(packet, buffer);

        assertEquals(packet, StageBackdropSwitchResultPacket.decode(buffer));
        buffer.release();
    }
}
