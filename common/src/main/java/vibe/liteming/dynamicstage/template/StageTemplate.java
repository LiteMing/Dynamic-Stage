package vibe.liteming.dynamicstage.template;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.flight.StageFlightCodec;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;
import vibe.liteming.dynamicstage.stage.StageInstance;
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.util.ContentHash;

import java.util.Arrays;
import java.util.List;

/** Portable, game-instance-level configuration used to create stage instances. */
public record StageTemplate(String id, ResourceLocation lodPackId, BlockPos lodAnchor,
                            StageBoundary boundary, StageClientScene clientScene, int capacity,
                            InstanceMode instanceMode, LifecyclePolicy lifecyclePolicy, byte[] flightJson,
                            CompoundTag arenaSnapshot, String flightName, BlockPos entryOffset,
                            List<StageStructurePlacement> structures) {
    public static final int FORMAT_VERSION = 2;
    private static final int MAX_STRUCTURES = 16;

    public StageTemplate {
        if (id == null || id.isBlank() || id.length() > 128) {
            throw new IllegalArgumentException("Invalid template id");
        }
        if (lodPackId == null || lodAnchor == null || boundary == null || clientScene == null
                || instanceMode == null || lifecyclePolicy == null || flightJson == null || arenaSnapshot == null
                || flightName == null || entryOffset == null || structures == null) {
            throw new IllegalArgumentException("Stage template contains null state");
        }
        if (capacity < 1 || capacity > StageSession.MAX_CAPACITY) {
            throw new IllegalArgumentException("Invalid template capacity: " + capacity);
        }
        flightJson = flightJson.clone();
        arenaSnapshot = arenaSnapshot.copy();
        structures = List.copyOf(structures);
        if (structures.size() > MAX_STRUCTURES || structures.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Invalid stage structure placement list");
        }
        if (!boundary.bounds(BlockPos.ZERO).contains(entryOffset.getX() + 0.5D,
                entryOffset.getY(), entryOffset.getZ() + 0.5D)) {
            throw new IllegalArgumentException("Stage entry offset is outside the boundary");
        }
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
                         InstanceMode instanceMode, LifecyclePolicy lifecyclePolicy, byte[] flightJson,
                         CompoundTag arenaSnapshot) {
        this(id, lodPackId, lodAnchor, boundary, clientScene, capacity, instanceMode, lifecyclePolicy,
                flightJson, arenaSnapshot, "", BlockPos.ZERO, List.of());
    }

    public StageTemplate(String id, ResourceLocation lodPackId, BlockPos lodAnchor,
                         StageBoundary boundary, StageClientScene clientScene, int capacity,
                         InstanceMode instanceMode, LifecyclePolicy lifecyclePolicy, byte[] flightJson,
                         CompoundTag arenaSnapshot, String flightName) {
        this(id, lodPackId, lodAnchor, boundary, clientScene, capacity, instanceMode, lifecyclePolicy,
                flightJson, arenaSnapshot, flightName, BlockPos.ZERO, List.of());
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

    @Override
    public List<StageStructurePlacement> structures() {
        return List.copyOf(structures);
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

    public boolean matches(StageInstance instance) {
        return id.equals(instance.stageId()) && lodPackId.equals(instance.lodPackId())
                && lodAnchor.equals(instance.lodAnchor()) && boundary.equals(instance.boundary())
                && capacity == instance.capacity() && flightHash().equals(instance.flightHash())
                && instance.persistent() == (lifecyclePolicy == LifecyclePolicy.RETAIN)
                && equivalentScene(clientScene, instance.clientScene());
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
        tag.putString("LifecyclePolicy", lifecyclePolicy.name());
        if (hasFlight()) {
            tag.putByteArray("Flight", flightJson);
            if (!flightName.isEmpty()) {
                tag.putString("FlightName", flightName);
            }
        }
        if (hasArenaSnapshot()) {
            tag.put("Arena", arenaSnapshot);
        }
        if (!entryOffset.equals(BlockPos.ZERO)) {
            tag.putLong("EntryOffset", entryOffset.asLong());
        }
        if (!structures.isEmpty()) {
            net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
            structures.forEach(placement -> list.add(placement.save()));
            tag.put("Structures", list);
        }
        return tag;
    }

    public static StageTemplate load(CompoundTag tag) {
        int format = tag.getInt("Format");
        if (format < 1 || format > FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported stage template format: " + tag.getInt("Format"));
        }
        if (!tag.contains("Boundary", Tag.TAG_COMPOUND) || !tag.contains("ClientScene", Tag.TAG_COMPOUND)) {
            throw new IllegalArgumentException("Stage template is missing scene data");
        }
        List<StageStructurePlacement> structures = format >= 2 && tag.contains("Structures", Tag.TAG_LIST)
                ? tag.getList("Structures", Tag.TAG_COMPOUND).stream()
                .map(value -> StageStructurePlacement.load((CompoundTag) value)).toList()
                : List.of();
        return new StageTemplate(tag.getString("Id"), new ResourceLocation(tag.getString("LodPack")),
                BlockPos.of(tag.getLong("LodAnchor")), StageBoundary.load(tag.getCompound("Boundary")),
                StageClientScene.load(tag.getCompound("ClientScene")), tag.getInt("Capacity"),
                InstanceMode.valueOf(tag.getString("InstanceMode")),
                loadLifecyclePolicy(tag), tag.getByteArray("Flight"),
                tag.contains("Arena", Tag.TAG_COMPOUND) ? tag.getCompound("Arena") : new CompoundTag(),
                tag.contains("FlightName", Tag.TAG_STRING) ? tag.getString("FlightName") : "",
                format >= 2 && tag.contains("EntryOffset", Tag.TAG_LONG)
                        ? BlockPos.of(tag.getLong("EntryOffset")) : BlockPos.ZERO,
                structures);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof StageTemplate that
                && id.equals(that.id) && lodPackId.equals(that.lodPackId) && lodAnchor.equals(that.lodAnchor)
                && boundary.equals(that.boundary) && clientScene.equals(that.clientScene) && capacity == that.capacity
                && instanceMode == that.instanceMode && lifecyclePolicy == that.lifecyclePolicy
                && Arrays.equals(flightJson, that.flightJson) && arenaSnapshot.equals(that.arenaSnapshot)
                && flightName.equals(that.flightName) && entryOffset.equals(that.entryOffset)
                && structures.equals(that.structures);
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(id, lodPackId, lodAnchor, boundary, clientScene,
                capacity, instanceMode, lifecyclePolicy, flightName, entryOffset, structures);
        result = 31 * result + Arrays.hashCode(flightJson);
        return 31 * result + arenaSnapshot.hashCode();
    }

    public enum InstanceMode { SHARED, PARALLEL }

    public enum LifecyclePolicy { RELEASE_WHEN_EMPTY, RETAIN }

    private static LifecyclePolicy loadLifecyclePolicy(CompoundTag tag) {
        if (tag.contains("LifecyclePolicy", Tag.TAG_STRING)) {
            return LifecyclePolicy.valueOf(tag.getString("LifecyclePolicy"));
        }
        // Format 1 originally called this a reset policy. Its actual useful
        // distinction maps directly onto whether an empty instance is retained.
        return "MANUAL".equals(tag.getString("ResetPolicy"))
                ? LifecyclePolicy.RETAIN : LifecyclePolicy.RELEASE_WHEN_EMPTY;
    }
}
