package vibe.liteming.dynamicstage.client.flight;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StageFlightPathTest {
    @Test
    void samplesEveryCameraAttributeRelativeToTheFirstPoint() {
        StageFlightPath path = path(0, "linear", false,
                point(10, 64, 20, 30, 5, 2, 70),
                point(20, 68, 16, 50, -5, 12, 50));

        StageFlightPose pose = path.sample(500.0D);
        assertNotNull(pose);

        assertVec(new Vec3(5, 2, -2), pose.positionOffset());
        assertEquals(10.0F, pose.yaw(), 0.0001F);
        assertEquals(-5.0F, pose.pitch(), 0.0001F);
        assertEquals(5.0F, pose.roll(), 0.0001F);
        assertEquals(70.0F, pose.referenceFov(), 0.0001F);
        assertEquals(60.0F, pose.fov(), 0.0001F);
    }

    @Test
    void finiteLoopClosesThenFinishesWithOneNormalPass() {
        StageFlightPath path = path(1, "linear", false,
                point(0, 64, 0, 0, 0, 0, 70),
                point(10, 64, 0, 0, 0, 0, 70));

        StageFlightPose loopStart = path.sample(1000.0D);
        StageFlightPose finalPass = path.sample(1500.0D);
        assertNotNull(loopStart);
        assertNotNull(finalPass);
        assertVec(Vec3.ZERO, loopStart.positionOffset());
        assertVec(new Vec3(5, 0, 0), finalPass.positionOffset());
        assertNull(path.sample(2000.0D));
    }

    @Test
    void endlessLoopNeverExpires() {
        StageFlightPath path = path(-1, "linear", false,
                point(0, 64, 0, 0, 0, 0, 70),
                point(10, 64, 0, 0, 0, 0, 70));

        StageFlightPose pose = path.sample(1_000_000_250.0D);
        assertNotNull(pose);
        assertVec(new Vec3(5, 0, 0), pose.positionOffset());
    }

    @Test
    void finalPassKeepsTheLoopExitTangent() {
        StageFlightPath path = path(1, "hermite", false,
                point(0, 64, 0, 0, 0, 0, 70),
                point(10, 64, 0, 0, 0, 0, 70),
                point(20, 64, 0, 0, 0, 0, 70));

        StageFlightPose pose = path.sample(1050.0D);
        assertNotNull(pose);

        assertEquals(-0.215D, pose.positionOffset().x, 0.0001D);
    }

    @Test
    void normalHermitePathUsesLinearEndpointTangents() {
        StageFlightPath path = path(0, "hermite", false,
                point(0, 64, 0, 0, 0, 0, 70),
                point(10, 64, 0, 0, 0, 0, 70),
                point(20, 64, 0, 0, 0, 0, 70));

        StageFlightPose pose = path.sample(50.0D);
        assertNotNull(pose);

        assertEquals(1.0D, pose.positionOffset().x, 0.0001D);
    }

    @Test
    void distanceTimingSpendsEqualTimePerTravelDistance() {
        StageFlightPath path = path(0, "linear", true,
                point(0, 0, 0, 0, 0, 0, 70),
                point(1, 0, 0, 0, 0, 0, 70),
                point(10, 0, 0, 0, 0, 0, 70));

        StageFlightPose pose = path.sample(500.0D);
        assertNotNull(pose);
        assertVec(new Vec3(5, 0, 0), pose.positionOffset());
    }

    private static StageFlightPath path(int loop, String interpolation, boolean distanceTiming,
                                        String... points) {
        String json = "{\"duration\":1000,\"loop\":" + loop + ",\"mode\":\"outside\","
                + "\"inter\":\"" + interpolation + "\",\"smooth_start\":false,\"pitch_mode\":0,"
                + "\"d_timing\":" + distanceTiming + ",\"points\":[" + String.join(",", points) + "]}";
        return StageFlightPath.parse(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String point(double x, double y, double z, double yaw, double pitch,
                                double roll, double fov) {
        return "{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z
                + ",\"rotationYaw\":" + yaw + ",\"rotationPitch\":" + pitch
                + ",\"roll\":" + roll + ",\"zoom\":" + fov + "}";
    }

    private static void assertVec(Vec3 expected, Vec3 actual) {
        assertEquals(expected.x, actual.x, 0.0001D);
        assertEquals(expected.y, actual.y, 0.0001D);
        assertEquals(expected.z, actual.z, 0.0001D);
    }
}
