package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class VoxyLodCollisionTest {
    @Test
    void findsShortestEscapeFromOccupiedVoxel() {
        AABB body = new AABB(0.8D, 0.1D, 0.1D, 1.4D, 1.7D, 0.9D);

        VoxyLodCollision.Escape escape = VoxyLodCollision.findEscape(body,
                (x, y, z) -> x == 1 && y == 0 && z == 0, Vec3.ZERO);

        assertEquals(Direction.WEST, escape.direction());
        assertEquals(0.4D, escape.distance(), 1.0E-7D);
    }

    @Test
    void tieEscapesAgainstIncomingMotion() {
        AABB body = new AABB(0.25D, 0.25D, 0.25D, 0.75D, 0.75D, 0.75D);

        VoxyLodCollision.Escape escape = VoxyLodCollision.findEscape(body,
                (x, y, z) -> x == 0 && y == 0 && z == 0, new Vec3(1.0D, 0.0D, 0.0D));

        assertEquals(Direction.WEST, escape.direction());
        assertEquals(0.75D, escape.distance(), 1.0E-7D);
    }

    @Test
    void reportsNoEscapeWhenVoxyDataIsEmpty() {
        assertNull(VoxyLodCollision.findEscape(new AABB(0.0D, 0.0D, 0.0D, 1.0D, 1.0D, 1.0D),
                (x, y, z) -> false, Vec3.ZERO));
    }
}
