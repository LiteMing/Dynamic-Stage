package vibe.liteming.dynamicstage.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import vibe.liteming.dynamicstage.network.StageTransitionPacket;
import vibe.liteming.dynamicstage.stage.StageTransferTicket;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the actual client timeline and server gate without an OpenGL context. */
class StageTransitionFlowTest {
    private static final long TICK = 50_000_000L;
    private static final long START = 1_000_000_000L;

    @Test
    void elapsedTicksAloneNeverAuthorizeMountOrTeleport() {
        StageTransitionTimeline client = new StageTransitionTimeline(40, START);
        StageTransferTicket server = ticket(true);
        assertEquals(1F, client.alpha(START + 20 * TICK));
        assertFalse(client.presented(), "No rendered frame, even though fade-in time elapsed");
        assertEquals(StageTransferTicket.Phase.WAITING_FRAME, server.phase());
        assertThrows(IllegalStateException.class, () -> server.transferred(START + 20 * TICK));
    }

    @Test
    void partialFrameDoesNotAuthorizeWorkAndOpaqueFrameDoesOnlyOnce() {
        StageTransitionTimeline client = new StageTransitionTimeline(40, START);
        client.drawn(START + 3 * TICK);
        assertFalse(client.presented());
        client.drawn(START + 10 * TICK);
        assertFalse(client.hasPresented(), "Gui render alone is not a display swap");
        assertTrue(client.presented());
        assertFalse(client.presented());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void entryAndExitWaitForPresentedFrameAndTargetFrame(boolean entering) {
        StageTransferTicket server = ticket(entering);
        StageTransitionPacket packet = server.packet();
        StageTransitionTimeline client = new StageTransitionTimeline(40, START);
        client.targetReady(START); // Early stale readiness must not reveal anything.
        assertFalse(client.revealing());
        client.drawn(START + 10 * TICK);
        assertTrue(client.presented());
        assertTrue(server.present(packet.transitionId(), packet.instanceId(), START + 10 * TICK));
        assertFalse(server.present(packet.transitionId(), packet.instanceId(), START + 11 * TICK));
        assertEquals(StageTransferTicket.Phase.PREPARING, server.phase());
        // No ClientLevel during transfer; no world game time is needed to advance the timeline.
        assertEquals(1F, client.alpha(START + 30 * TICK));
        server.transferred(START + 40 * TICK);
        client.arrived(START + 40 * TICK);
        // LOD activation may take many ticks; keep the frame opaque throughout.
        assertEquals(1F, client.alpha(START + 60 * TICK));
        client.targetReady(START + 60 * TICK);
        assertFalse(client.finished(START + 65 * TICK));
        assertTrue(client.finished(START + 90 * TICK));
    }

    @ParameterizedTest
    @ValueSource(ints = {20, 40, 200})
    void configuredDurationControlsBothFadePhases(int ticks) {
        StageTransitionTimeline client = new StageTransitionTimeline(ticks, START);
        long in = Math.max(2, Math.round(ticks * .25F)) * TICK;
        long out = Math.max(2, ticks - Math.round(ticks * .25F)) * TICK;
        assertEquals(.5F, client.alpha(START + in / 2), .001F);
        client.drawn(START + in);
        assertTrue(client.presented());
        client.targetReady(START + in);
        assertEquals(.5F, client.alpha(START + in + out / 2), .001F);
        assertEquals(0F, client.alpha(START + in + out));
    }

    @Test
    void delayedOrWrongInstanceAcknowledgementCannotOpenNewTransfer() {
        StageTransferTicket old = ticket(true);
        StageTransferTicket next = new StageTransferTicket(new StageTransitionPacket(
                UUID.randomUUID(), old.packet().instanceId(), false, 40), START);
        assertFalse(next.present(old.packet().transitionId(), old.packet().instanceId(), START));
        assertFalse(next.present(next.packet().transitionId(), UUID.randomUUID(), START));
        assertEquals(StageTransferTicket.Phase.WAITING_FRAME, next.phase());
    }

    @Test
    void expiredHandshakeDoesNotTeleport() {
        StageTransferTicket server = ticket(true);
        assertTrue(server.expired(START + 16_000_000_000L));
        assertFalse(server.present(server.packet().transitionId(), server.packet().instanceId(), START + 16_000_000_000L));
        assertEquals(StageTransferTicket.Phase.WAITING_FRAME, server.phase());
    }

    @Test
    void longPackagePreparationGetsFreshTargetDeadlineAfterDimensionChange() {
        StageTransitionTimeline client = new StageTransitionTimeline(40, START);
        client.drawn(START + 10 * TICK);
        client.presented();
        long afterDownload = START + 120_000_000_000L;
        assertFalse(client.timedOut(afterDownload, true));
        client.arrived(afterDownload);
        assertFalse(client.timedOut(afterDownload + 5_000_000_000L, false));
        assertTrue(client.timedOut(afterDownload + 31_000_000_000L, false));
    }

    @Test
    void failedTransferRemainsOpaqueButHasBoundedLocalFallback() {
        StageTransitionTimeline client = new StageTransitionTimeline(40, START);
        client.fail(START + TICK);
        assertEquals(1F, client.alpha(START + 2 * TICK));
        assertFalse(client.presented());
        client.targetReady(START + 2 * TICK);
        assertFalse(client.revealing());
        assertFalse(client.fallbackDue(START + 2_000_000_000L));
        assertTrue(client.fallbackDue(START + 4_000_000_000L));
    }

    @Test
    void acknowledgedRollbackCanRevealOnlyAfterReplacementFrameIsReady() {
        StageTransitionTimeline client = new StageTransitionTimeline(40, START);
        client.drawn(START + 10 * TICK);
        client.presented();
        client.fail(START + 30 * TICK);
        client.cancelFailure();
        assertEquals(1F, client.alpha(START + 35 * TICK));
        client.targetReady(START + 40 * TICK);
        assertTrue(client.finished(START + 70 * TICK));
    }

    private static StageTransferTicket ticket(boolean entering) {
        return new StageTransferTicket(new StageTransitionPacket(UUID.randomUUID(), UUID.randomUUID(), entering, 40), START);
    }
}
