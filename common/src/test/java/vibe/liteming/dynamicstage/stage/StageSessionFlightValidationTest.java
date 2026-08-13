package vibe.liteming.dynamicstage.stage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static vibe.liteming.dynamicstage.util.ContentHash.sha256Hex;

class StageSessionFlightValidationTest {

    @Test
    void acceptsLegacyEmptyAndValidPersistedFlightState() {
        String hash = sha256Hex("flight".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertDoesNotThrow(() -> StageSession.validateFlight("", 0, 0L, -1L));
        assertDoesNotThrow(() -> StageSession.validateFlight(hash, 128, 5000L, -1L));
        assertDoesNotThrow(() -> StageSession.validateFlight(hash, 128, 5000L, 12_345L));
    }

    @Test
    void rejectsPartialOrOutOfRangeFlightState() {
        String hash = sha256Hex("flight".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> StageSession.validateFlight("", 1, 0L, -1L));
        assertThrows(IllegalArgumentException.class, () -> StageSession.validateFlight("bad", 128, 5000L, -1L));
        assertThrows(IllegalArgumentException.class, () -> StageSession.validateFlight(hash, 0, 5000L, -1L));
        assertThrows(IllegalArgumentException.class, () -> StageSession.validateFlight(hash, 128, 1L, -1L));
        assertThrows(IllegalArgumentException.class, () -> StageSession.validateFlight(hash, 128, 5000L, -2L));
    }
}
