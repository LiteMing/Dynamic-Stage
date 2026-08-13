package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import vibe.liteming.dynamicstage.client.flight.StageFlightController;
import vibe.liteming.dynamicstage.client.flight.StageFlightPose;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.world.StageWorlds;

/** Shared virtual camera used by native LOD backends. */
public final class StageLodCamera {
    private StageLodCamera() {
    }

    @Nullable
    public static Snapshot snapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientStageSession.Snapshot session = ClientStageSession.active();
        if (minecraft.level == null || !StageWorlds.isStageLevel(minecraft.level)
                || session == null || !DhBackdropRuntime.isMounted(session.instanceId())) {
            return null;
        }
        float partialTick = minecraft.getFrameTime();
        Vec3 position = StageCoordinateMapper.cameraPosition(session);
        if (position == null) {
            return null;
        }
        StageFlightPose flight = StageFlightController.currentPose(partialTick);
        return new Snapshot(flight == null ? position : position.add(flight.positionOffset()), flight);
    }

    public static Matrix4f modelView(Matrix4f original, @Nullable StageFlightPose flight) {
        if (flight == null) {
            return original;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        return modelView(original, camera.getYRot(), camera.getXRot(), flight);
    }

    static Matrix4f modelView(Matrix4f original, float playerYaw, float playerPitch,
                              StageFlightPose flight) {
        Matrix4f playerRotation = cameraRotation(playerYaw, playerPitch, 0.0F);
        Matrix4f flightRotation = cameraRotation(playerYaw + flight.yaw(),
                playerPitch + flight.pitch(), flight.roll());
        Matrix4f correction = playerRotation.invert(new Matrix4f()).mul(flightRotation);
        return new Matrix4f(original).mul(correction);
    }

    public static Matrix4f projection(Matrix4f original, @Nullable StageFlightPose flight) {
        if (flight == null) {
            return original;
        }
        float scale = flight.projectionScale();
        return new Matrix4f(original).scale(scale, scale, 1.0F);
    }

    @Nullable
    public static Vec3 lookVector(@Nullable StageFlightPose flight) {
        if (flight == null) {
            return null;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        return lookVector(camera.getYRot(), camera.getXRot(), flight);
    }

    static Vec3 lookVector(float playerYaw, float playerPitch, StageFlightPose flight) {
        double yaw = Math.toRadians(-(playerYaw + flight.yaw()));
        double pitch = Math.toRadians(playerPitch + flight.pitch());
        double cosPitch = Math.cos(pitch);
        return new Vec3(Math.sin(yaw) * cosPitch, -Math.sin(pitch),
                Math.cos(yaw) * cosPitch);
    }

    private static Matrix4f cameraRotation(float yaw, float pitch, float roll) {
        return new Matrix4f()
                .rotateZ((float) Math.toRadians(roll))
                .rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw + 180.0F));
    }

    public record Snapshot(Vec3 position, @Nullable StageFlightPose flight) {
    }
}
