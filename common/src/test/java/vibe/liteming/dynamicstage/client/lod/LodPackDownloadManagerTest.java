package vibe.liteming.dynamicstage.client.lod;

import org.junit.jupiter.api.Test;
import vibe.liteming.dynamicstage.lod.LodPackageOffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LodPackDownloadManagerTest {
    private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void disabledClientRejectsRequiredOffer() {
        LodPackageOffer offer = offer(LodPackageOffer.Delivery.REQUIRED, 16L);

        LodPackDownloadManager.Result result = LodPackDownloadManager.rejectByClientPolicy(
                offer, false, 256L * 1024L * 1024L, 256);

        assertEquals(LodPackDownloadManager.Status.FAILED, result.status());
    }

    @Test
    void disabledClientWarnsForOptionalOffer() {
        LodPackageOffer offer = offer(LodPackageOffer.Delivery.OPTIONAL, 16L);

        LodPackDownloadManager.Result result = LodPackDownloadManager.rejectByClientPolicy(
                offer, false, 256L * 1024L * 1024L, 256);

        assertEquals(LodPackDownloadManager.Status.OPTIONAL_MISSING, result.status());
    }

    @Test
    void oversizedOfferIsRejectedBeforeDownload() {
        LodPackageOffer offer = offer(LodPackageOffer.Delivery.REQUIRED, 65L * 1024L * 1024L);

        LodPackDownloadManager.Result result = LodPackDownloadManager.rejectByClientPolicy(
                offer, true, 64L * 1024L * 1024L, 64);

        assertEquals(LodPackDownloadManager.Status.FAILED, result.status());
    }

    @Test
    void acceptedOfferHasNoPolicyResult() {
        LodPackageOffer offer = offer(LodPackageOffer.Delivery.REQUIRED, 64L * 1024L * 1024L);

        assertNull(LodPackDownloadManager.rejectByClientPolicy(
                offer, true, 64L * 1024L * 1024L, 64));
    }

    private static LodPackageOffer offer(LodPackageOffer.Delivery delivery, long bytes) {
        return new LodPackageOffer(delivery, "https://example.invalid/pack.dstlod", bytes, HASH);
    }
}
