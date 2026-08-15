package vibe.liteming.dynamicstage.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import vibe.liteming.dynamicstage.lod.LodPackageOffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LodDownloadPacketTest {
    private static final ResourceLocation ID = new ResourceLocation("minecraft", "gr");
    private static final String HASH = "ab".repeat(32);

    @Test
    void roundTripsRequest() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        LodDownloadRequestPacket expected = new LodDownloadRequestPacket(ID, HASH, 1234L);
        LodDownloadRequestPacket.encode(expected, buffer);
        assertEquals(expected, LodDownloadRequestPacket.decode(buffer));
        buffer.release();
    }

    @Test
    void roundTripsBoundedChunk() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        byte[] data = {1, 2, 3, 4};
        LodDownloadChunkPacket expected = new LodDownloadChunkPacket(ID, HASH, 8L, data, true);
        LodDownloadChunkPacket actual;
        LodDownloadChunkPacket.encode(expected, buffer);
        actual = LodDownloadChunkPacket.decode(buffer);
        assertEquals(expected.lodPackId(), actual.lodPackId());
        assertEquals(expected.sha256(), actual.sha256());
        assertEquals(expected.offset(), actual.offset());
        assertArrayEquals(data, actual.data());
        assertEquals(expected.complete(), actual.complete());
        buffer.release();
    }

    @Test
    void decodesLegacyHttpOfferWithoutTransportFlag() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        buffer.writeEnum(LodPackageOffer.Delivery.OPTIONAL);
        buffer.writeUtf("https://example.invalid/gr.dstlod", LodPackageOffer.MAX_URL_LENGTH);
        buffer.writeVarLong(1024L);
        buffer.writeUtf(HASH, 64);

        LodPackageOffer offer = LodPackageOffer.decode(buffer);

        assertEquals(false, offer.serverHosted());
        buffer.release();
    }
}
