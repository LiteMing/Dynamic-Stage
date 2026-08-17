package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.client.config.StageClientConfig;
import vibe.liteming.dynamicstage.client.flight.StageFlightController;
import vibe.liteming.dynamicstage.client.flight.StageFlightPose;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;
import vibe.liteming.dynamicstage.network.StageLodCollisionPacket;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.util.UUID;

/** Experimental read-only Voxy collision probe that reports contacts to the server. */
public final class VoxyLodCollision {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyLodCollision.class);
    private static final double BODY_INSET = 0.035D;
    private static final int QUERY_INTERVAL_TICKS = 2;
    private static final int REPEAT_REPORT_TICKS = 10;
    @Nullable private static UUID activeInstance;
    @Nullable private static Direction lastDirection;
    private static int lastReportTick = Integer.MIN_VALUE;
    private static boolean colliding;
    private static boolean queryFailed;
    private static boolean queryWarningLogged;

    private VoxyLodCollision() {
    }

    public static void tick() {
        if (!StageClientConfig.experimentalVoxyCollision()) {
            reset();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        ClientStageSession.Snapshot snapshot = ClientStageSession.active();
        if (player == null || minecraft.level == null || !StageWorlds.isStageLevel(minecraft.level)
                || snapshot == null || !snapshot.clientScene().lodVisible()
                || !StageBackdropRuntime.isVoxyMounted()
                || !StageBackdropRuntime.isMounted(snapshot.instanceId()) || player.isSpectator()) {
            reset();
            return;
        }
        if (!snapshot.instanceId().equals(activeInstance)) {
            reset();
            activeInstance = snapshot.instanceId();
        }
        if (queryFailed || Math.floorMod(player.tickCount, QUERY_INTERVAL_TICKS) != 0) {
            return;
        }
        Vec3 mappedPlayer = StageCoordinateMapper.playerPosition(snapshot);
        if (mappedPlayer == null) {
            return;
        }
        StageFlightPose flight = StageFlightController.currentPose(0.0F);
        if (flight != null) {
            mappedPlayer = mappedPlayer.add(flight.positionOffset());
        }
        AABB body = player.getBoundingBox()
                .move(mappedPlayer.subtract(player.position()))
                .inflate(-BODY_INSET, -BODY_INSET, -BODY_INSET);
        try (VoxyBackdropRuntime.BlockLookup lookup = VoxyBackdropRuntime.openBlockLookup()) {
            if (lookup == null) {
                return;
            }
            Escape escape = findEscape(body, lookup::isSolid, player.getDeltaMovement());
            if (escape == null) {
                colliding = false;
                lastDirection = null;
                return;
            }
            boolean report = !colliding || escape.direction() != lastDirection
                    || player.tickCount - lastReportTick >= REPEAT_REPORT_TICKS;
            colliding = true;
            lastDirection = escape.direction();
            if (report) {
                DynamicStageNetwork.reportLodCollision(new StageLodCollisionPacket(snapshot.instanceId(),
                        escape.direction(), (float) escape.distance()));
                lastReportTick = player.tickCount;
            }
        } catch (RuntimeException e) {
            queryFailed = true;
            if (!queryWarningLogged) {
                queryWarningLogged = true;
                LOGGER.warn("Experimental Voxy LOD collision reporting was disabled for this stage: {}",
                        rootMessage(e));
            }
        }
    }

    @Nullable
    static Escape findEscape(AABB box, SolidLookup lookup, Vec3 preferredDirection) {
        int minX = (int) Math.floor(box.minX);
        int maxX = (int) Math.ceil(box.maxX) - 1;
        int minY = (int) Math.floor(box.minY);
        int maxY = (int) Math.ceil(box.maxY) - 1;
        int minZ = (int) Math.floor(box.minZ);
        int maxZ = (int) Math.ceil(box.maxZ) - 1;
        Candidate best = null;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    if (!lookup.isSolid(x, y, z) || !box.intersects(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D)) {
                        continue;
                    }
                    best = choose(best, new Candidate(Direction.WEST,
                            x - box.maxX, preferredDirection));
                    best = choose(best, new Candidate(Direction.EAST,
                            x + 1.0D - box.minX, preferredDirection));
                    best = choose(best, new Candidate(Direction.DOWN,
                            y - box.maxY, preferredDirection));
                    best = choose(best, new Candidate(Direction.UP,
                            y + 1.0D - box.minY, preferredDirection));
                    best = choose(best, new Candidate(Direction.NORTH,
                            z - box.maxZ, preferredDirection));
                    best = choose(best, new Candidate(Direction.SOUTH,
                            z + 1.0D - box.minZ, preferredDirection));
                }
            }
        }
        return best == null ? null : new Escape(best.direction(), Math.abs(best.distance()));
    }

    private static Candidate choose(@Nullable Candidate current, Candidate candidate) {
        if (candidate.distance() == 0.0D) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        double candidateDistance = Math.abs(candidate.distance());
        double currentDistance = Math.abs(current.distance());
        if (candidateDistance < currentDistance - 1.0E-7D) {
            return candidate;
        }
        if (Math.abs(candidateDistance - currentDistance) <= 1.0E-7D
                && directionVector(candidate.direction()).dot(candidate.preferred().scale(-1.0D))
                > directionVector(current.direction()).dot(current.preferred().scale(-1.0D))) {
            return candidate;
        }
        return current;
    }

    public static void reset() {
        activeInstance = null;
        lastDirection = null;
        lastReportTick = Integer.MIN_VALUE;
        colliding = false;
        queryFailed = false;
        queryWarningLogged = false;
    }

    private static Vec3 directionVector(Direction direction) {
        return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    @FunctionalInterface
    interface SolidLookup {
        boolean isSolid(int x, int y, int z);
    }

    record Escape(Direction direction, double distance) {
    }

    private record Candidate(Direction direction, double distance, Vec3 preferred) {
    }
}
