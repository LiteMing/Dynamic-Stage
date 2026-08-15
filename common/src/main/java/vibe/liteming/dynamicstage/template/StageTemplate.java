package vibe.liteming.dynamicstage.template;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.flight.StageFlightCodec;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.util.ContentHash;

import java.util.Arrays;

/** Portable, game-instance-level configuration used to create stage instances. */
public record StageTemplate(String id, ResourceLocation lodPackId, BlockPos lodAnchor,
                            StageBoundary boundary, StageClientScene clientScene, int capacity,
                            InstanceMode instanceMode, ResetPolicy resetPolicy, byte[] flightJson,
                            CompoundTag arenaSnapshot, String flightName) {
    public static final int FORMAT_VERSION = 1;

    public StageTemplate {
        if (id == null || id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("Invalid template id");
        }
        if (lodPackId == null || lodAnchor == null || boundary == null || clientScene == null
                || instanceMode == null || resetPolicy == null || flightJson == null || arenaSnapshot == null
                || flightName == null) {
            throw new IllegalArgumentException("Stage template contains null state");
        }
        if (capacity < 1 || capacity > StageSession.MAX_CAPACITY) {
            throw new IllegalArgumentException("Invalid template capacity: " + capacity);
        }
        flightJson = flightJson.clone();
        arenaSnapshot = arenaSnapshot.copy();
        if (!flightName.isEmpty() && !validFlightName(flightName)) {
            throw new IllegalArgumentException("Invalid template flight name");
        }
        if (flightJson.length == 0 && !flightName.isEmpty()) {
            throw new IllegalArgumentException("Template flight name requires Flight data");
        }
        if (flightJson.length > 0) {
            try {
                StageFlightCodec.readSingle(flightJson);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("Invalid stage template flight", e);
            }
        }
    }

    public StageTemplate(String id, ResourceLocation lodPackId, BlockPos lodAnchor,
                         StageBoundary boundary, StageClientScene clientScene, int capacity,
                         InstanceMode instanceMode, ResetPolicy resetPolicy, byte[] flightJson,
                         CompoundTag arenaSnapshot) {
        this(id, lodPackId, lodAnchor, boundary, clientScene, capacity, instanceMode, resetPolicy,
                flightJson, arenaSnapshot, "");
    }

    public static boolean validFlightName(String name) {
        return name != null && name.matches("[A-Za-z0-9_-]{1,64}");
    }

    @Override
    public byte[] flightJson() {
        return flightJson.clone();
    }

    @Override
    public CompoundTag arenaSnapshot() {
        return arenaSnapshot.copy();
    }

    public boolean hasFlight() {
        return flightJson.length > 0;
    }

    public boolean hasArenaSnapshot() {
        return !arenaSnapshot.isEmpty();
    }

    public String flightHash() {
        return hasFlight() ? ContentHash.sha256Hex(flightJson) : "";
    }

    public StageClientScene sceneForNewInstance(long overworldDayTime, long gameTime) {
        long baseDayTime = clientScene.timeMode() == StageClientScene.TimeMode.FOLLOW
                ? overworldDayTime : clientScene.timeBaseDayTime();
        return new StageClientScene(clientScene.followPlayer(), clientScene.lodMovementScale(),
                clientScene.dhNearFadeScale(), clientScene.voxyNearCulling(), clientScene.lodVisible(), clientScene.lodBlurRadius(), clientScene.lodTransition(),
                clientScene.lodTransitionTicks(), gameTime, clientScene.timeMode(), baseDayTime, gameTime,
                clientScene.timeCycleTicks(), clientScene.skyMode());
    }

    public boolean matches(StageSession session) {
        return id.equals(session.stageId()) && lodPackId.equals(session.lodPackId())
                && lodAnchor.equals(session.lodAnchor()) && boundary.equals(session.boundary())
                && capacity == session.capacity() && flightHash().equals(session.flightHash())
                && equivalentScene(clientScene, session.clientScene());
    }

    private static boolean equivalentScene(StageClientScene expected, StageClientScene actual) {
        return expected.followPlayer() == actual.followPlayer()
                && Float.compare(expected.lodMovementScale(), actual.lodMovementScale()) == 0
                && Float.compare(expected.dhNearFadeScale(), actual.dhNearFadeScale()) == 0
                && expected.voxyNearCulling() == actual.voxyNearCulling()
                && expected.lodVisible() == actual.lodVisible()
                && Float.compare(expected.lodBlurRadius(), actual.lodBlurRadius()) == 0
                && expected.lodTransition() == actual.lodTransition()
                && expected.lodTransitionTicks() == actual.lodTransitionTicks()
                && expected.timeMode() == actual.timeMode()
                && (expected.timeMode() == StageClientScene.TimeMode.FOLLOW
                || expected.timeBaseDayTime() == actual.timeBaseDayTime())
                && expected.timeCycleTicks() == actual.timeCycleTicks()
                && expected.skyMode() == actual.skyMode();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Format", FORMAT_VERSION);
        tag.putString("Id", id);
        tag.putString("LodPack", lodPackId.toString());
        tag.putLong("LodAnchor", lodAnchor.asLong());
        tag.put("Boundary", boundary.save());
        tag.put("ClientScene", clientScene.save());
        tag.putInt("Capacity", capacity);
        tag.putString("InstanceMode", instanceMode.name());
        tag.putString("ResetPolicy", resetPolicy.name());
        if (hasFlight()) {
            tag.putByteArray("Flight", flightJson);
            if (!flightName.isEmpty()) {
                tag.putString("FlightName", flightName);
            }
        }
        if (hasArenaSnapshot()) {
            tag.put("Arena", arenaSnapshot);
        }
        return tag;
    }

    public static StageTemplate load(CompoundTag tag) {
        if (tag.getInt("Format") != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported stage template format: " + tag.getInt("Format"));
        }
        if (!tag.contains("Boundary", Tag.TAG_COMPOUND) || !tag.contains("ClientScene", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Stage template is missing scene data");
        }
        return new StageTemplate(tag.getString("Id"), new ResourceLocation(tag.getString("LodPack")),
                BlockPos.of(tag.getLong("LodAnchor")), StageBoundary.load(tag.getCompound("Boundary")),
                StageClientScene.load(tag.getCompound("ClientScene")), tag.getInt("Capacity"),
                InstanceMode.valueOf(tag.getString("InstanceMode")),
                ResetPolicy.valueOf(tag.getString("ResetPolicy")), tag.getByteArray("Flight"),
                tag.contains("Arena", Tag.TAG_COMPOUND) ? tag.getCompound("Arena") : new CompoundTag(),
                tag.contains("FlightName", Tag.TAG_STRING) ? tag.getString("FlightName") : "");
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StageTemplate that
                && id.equals(that.id) && lodPackId.equals(that.lodPackId) && lodAnchor.equals(that.lodAnchor)
                && boundary.equals(that.boundary) && clientScene.equals(that.clientScene) && capacity == that.capacity
                && instanceMode == that.instanceMode && resetPolicy == that.resetPolicy
                && Arrays.equals(flightJson, that.flightJson) && arenaSnapshot.equals(that.arenaSnapshot)
                && flightName.equals(that.flightName);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(id, lodPackId, lodAnchor, boundary, clientScene,
                capacity, instanceMode, resetPolicy, flightName);
        result = 31 * result + Arrays.hashCode(flightJson);
        return 31 * result + arenaSnapshot.hashCode();
    }

    public enum InstanceMode { SHARED, PARALLEL }

    public enum ResetPolicy { ON_CREATE, MANUAL }
}
