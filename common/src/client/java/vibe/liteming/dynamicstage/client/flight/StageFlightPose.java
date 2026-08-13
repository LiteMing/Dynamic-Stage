package vibe.liteming.dynamicstage.client.flight;

import net.minecraft.world.phys.Vec3;

/** Relative camera transform contributed by a client-only stage flight. */
public record StageFlightPose(Vec3 positionOffset, float yaw, float pitch, float roll,
                              float referenceFov, float fov) {
    public float projectionScale() {
        double reference = Math.tan(Math.toRadians(referenceFov) * 0.5D);
        double current = Math.tan(Math.toRadians(fov) * 0.5D);
        return (float) (reference / current);
    }
}
