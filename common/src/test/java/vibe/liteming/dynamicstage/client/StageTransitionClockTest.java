package vibe.liteming.dynamicstage.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageTransitionClockTest {
    @Test
    void survivesClientLevelGameTimeReset() {
        StageTransitionClock clock = new StageTransitionClock(1_000_000_000L, 20);

        assertFalse(clock.reached(1_500_000_000L));
        // A new ClientLevel can reset gameTime to zero; the monotonic clock is unchanged.
        assertTrue(clock.reached(2_000_000_000L));
    }
}
