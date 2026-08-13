package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.joml.Matrix4f;
import vibe.liteming.dynamicstage.client.flight.StageFlightPose;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageLodCameraTest {
    @Test
    void flightAnglesAreRelativeToThePlayerCamera() {
        StageFlightPose pose = new StageFlightPose(Vec3.ZERO, 90.0F, 30.0F,
                15.0F, 70.0F, 70.0F);

        Vec3 look = StageLodCamera.lookVector(30.0F, -10.0F, pose);

        double yaw = Math.toRadians(-120.0D);
        double pitch = Math.toRadians(20.0D);
        assertEquals(Math.sin(yaw) * Math.cos(pitch), look.x, 0.0001D);
        assertEquals(-Math.sin(pitch), look.y, 0.0001D);
        assertEquals(Math.cos(yaw) * Math.cos(pitch), look.z, 0.0001D);
    }

    @Test
    void rollDoesNotChangeTheForwardVector() {
        StageFlightPose pose = new StageFlightPose(Vec3.ZERO, 0.0F, 0.0F,
                90.0F, 70.0F, 70.0F);

        Vec3 look = StageLodCamera.lookVector(0.0F, 0.0F, pose);

        assertEquals(0.0D, look.x, 0.0001D);
        assertEquals(0.0D, look.y, 0.0001D);
        assertEquals(1.0D, look.z, 0.0001D);
    }

    @Test
    void modelViewPreservesPlayerRollAndAppliesTheRelativeFlightPose() {
        float playerYaw = 30.0F;
        float playerPitch = -10.0F;
        float playerRoll = 5.0F;
        StageFlightPose pose = new StageFlightPose(Vec3.ZERO, 90.0F, 30.0F,
                15.0F, 70.0F, 70.0F);
        Matrix4f original = cameraRotation(playerYaw, playerPitch, playerRoll);

        Matrix4f actual = StageLodCamera.modelView(original, playerYaw, playerPitch, pose);
        Matrix4f expected = cameraRotation(playerYaw + pose.yaw(),
                playerPitch + pose.pitch(), playerRoll + pose.roll());

        assertTrue(expected.equals(actual, 0.0001F));
    }

    @Test
    void flightTransformsDoNotMutateSharedRenderMatrices() {
        StageFlightPose pose = new StageFlightPose(Vec3.ZERO, 35.0F, -20.0F,
                12.0F, 70.0F, 48.0F);
        Matrix4f modelView = cameraRotation(10.0F, 5.0F, 0.0F);
        Matrix4f projection = new Matrix4f().perspective(
                (float) Math.toRadians(70.0D), 16.0F / 9.0F, 0.05F, 1000.0F);
        Matrix4f originalModelView = new Matrix4f(modelView);
        Matrix4f originalProjection = new Matrix4f(projection);

        StageLodCamera.modelView(modelView, 10.0F, 5.0F, pose);
        StageLodCamera.projection(projection, pose);

        assertTrue(originalModelView.equals(modelView, 0.0F));
        assertTrue(originalProjection.equals(projection, 0.0F));
    }

    private static Matrix4f cameraRotation(float yaw, float pitch, float roll) {
        return new Matrix4f()
                .rotateZ((float) Math.toRadians(roll))
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw + 180.0F));
    }
}
