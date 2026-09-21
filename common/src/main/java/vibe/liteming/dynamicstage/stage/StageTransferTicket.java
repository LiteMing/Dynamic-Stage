package vibe.liteming.dynamicstage.stage;

import vibe.liteming.dynamicstage.network.StageTransitionPacket;
import java.util.UUID;

/** Server gate: a timer alone must never authorize destructive client preparation. */
public final class StageTransferTicket {
    public enum Phase { WAITING_FRAME, PREPARING, TRANSFERRED }
    private final StageTransitionPacket packet;
    private Phase phase = Phase.WAITING_FRAME;
    private long deadline;

    public StageTransferTicket(StageTransitionPacket packet, long now) {
        this.packet = packet;
        deadline = now + 15_000_000_000L;
    }

    public StageTransitionPacket packet() { return packet; }
    public Phase phase() { return phase; }
    public boolean matches(UUID transition, UUID instance) {
        return packet.transitionId().equals(transition) && packet.instanceId().equals(instance);
    }

    public boolean present(UUID transition, UUID instance, long now) {
        if (!matches(transition, instance) || phase != Phase.WAITING_FRAME || expired(now)) return false;
        phase = Phase.PREPARING;
        deadline = now + 600_000_000_000L; // Allow the existing bounded package download flow.
        return true;
    }

    public void transferred(long now) {
        if (phase != Phase.PREPARING) throw new IllegalStateException("Transfer before presented frame");
        phase = Phase.TRANSFERRED;
        deadline = now + 30_000_000_000L;
    }

    public boolean expired(long now) { return now - deadline >= 0L; }
}
