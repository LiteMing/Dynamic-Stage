package vibe.liteming.dynamicstage.client.flight;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StageFlightPoseTest {
    @Test
    void projectionScaleIsIdentityAtReferenceFov() {
        assertEquals(1.0F, new StageFlightPose(Vec3.ZERO, 0, 0, 0, 70, 70)
                .projectionScale(), 0.0001F);
    }

    @Test
    void narrowerFovMagnifiesTheLodProjection() {
        StageFlightPose pose = new StageFlightPose(Vec3.ZERO, 0, 0, 0, 70, 35);

        assertEquals(2.2202F, pose.projectionScale(), 0.001F);
    }
}
