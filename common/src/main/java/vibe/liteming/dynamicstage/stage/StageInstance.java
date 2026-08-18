package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.template.StageTemplate;

import java.util.UUID;

/** Persisted stage state that remains meaningful even when no players are members. */
public record StageInstance(
        UUID instanceId,
        String stageId,
        ResourceLocation lodPackId,
        BlockPos lodAnchor,
        int slot,
        int capacity,
        StageBoundary boundary,
        StageClientScene clientScene,
        boolean persistent,
        StageTemplate.InteractionPolicy interactionPolicy,
        String flightHash,
        int flightBytes,
        long flightDurationMillis,
        long flightStartGameTime
) {
    public StageInstance {
        if (instanceId == null || lodPackId == null || lodAnchor == null || boundary == null
                || clientScene == null || interactionPolicy == null) {
            throw new IllegalArgumentException("Stage instance contains null state");
        }
        if (stageId == null || stageId.isBlank() || stageId.length() > 128) {
            throw new IllegalArgumentException("Invalid stage id");
        }
        if (slot < 0 || slot >= StagePlacement.MAX_SLOTS) {
            throw new IllegalArgumentException("Invalid stage slot: " + slot);
        }
        if (capacity < 1 || capacity > StageSession.MAX_CAPACITY) {
            throw new IllegalArgumentException("Invalid stage capacity: " + capacity);
        }
        StageSession.validateFlight(flightHash, flightBytes, flightDurationMillis, flightStartGameTime);
    }

    public static StageInstance from(StageSession session, boolean persistent) {
        return from(session, persistent, StageTemplate.InteractionPolicy.ADVENTURE);
    }

    public static StageInstance from(StageSession session, boolean persistent,
                                     StageTemplate.InteractionPolicy interactionPolicy) {
        return new StageInstance(session.instanceId(), session.stageId(), session.lodPackId(),
                session.lodAnchor(), session.slot(), session.capacity(), session.boundary(), session.clientScene(),
                persistent, interactionPolicy, session.flightHash(), session.flightBytes(), session.flightDurationMillis(),
                session.flightStartGameTime());
    }

    public BlockPos stageOrigin() {
        return StagePlacement.originForSlot(slot);
    }

    public boolean hasFlight() {
        return !flightHash.isEmpty();
    }

    public StageInstance withLodAnchor(BlockPos anchor) {
        return copy(stageId, lodPackId, anchor, capacity, boundary, clientScene, persistent,
                interactionPolicy, flightHash, flightBytes, flightDurationMillis, flightStartGameTime);
    }

    public StageInstance withLodPack(ResourceLocation pack) {
        return copy(stageId, pack, lodAnchor, capacity, boundary, clientScene, persistent,
                interactionPolicy, flightHash, flightBytes, flightDurationMillis, flightStartGameTime);
    }

    public StageInstance withTemplateSettings(String newStageId, ResourceLocation pack, BlockPos anchor,
                                              int newCapacity, StageBoundary newBoundary,
                                              StageClientScene scene, boolean newPersistent,
                                              StageTemplate.InteractionPolicy newInteractionPolicy) {
        return copy(newStageId, pack, anchor, newCapacity, newBoundary, scene, newPersistent,
                newInteractionPolicy, flightHash, flightBytes, flightDurationMillis, flightStartGameTime);
    }

    public StageInstance withBoundary(StageBoundary newBoundary) {
        return copy(stageId, lodPackId, lodAnchor, capacity, newBoundary, clientScene, persistent,
                interactionPolicy, flightHash, flightBytes, flightDurationMillis, flightStartGameTime);
    }

    public StageInstance withClientScene(StageClientScene scene) {
        return copy(stageId, lodPackId, lodAnchor, capacity, boundary, scene, persistent,
                interactionPolicy, flightHash, flightBytes, flightDurationMillis, flightStartGameTime);
    }

    public StageInstance withPersistent(boolean value) {
        return copy(stageId, lodPackId, lodAnchor, capacity, boundary, clientScene, value,
                interactionPolicy, flightHash, flightBytes, flightDurationMillis, flightStartGameTime);
    }

    public StageInstance withInteractionPolicy(StageTemplate.InteractionPolicy policy) {
        return copy(stageId, lodPackId, lodAnchor, capacity, boundary, clientScene, persistent,
                policy, flightHash, flightBytes, flightDurationMillis, flightStartGameTime);
    }

    public StageInstance withFlight(String hash, int bytes, long durationMillis, long startGameTime) {
        return copy(stageId, lodPackId, lodAnchor, capacity, boundary, clientScene, persistent,
                interactionPolicy, hash, bytes, durationMillis, startGameTime);
    }

    public StageInstance withFlightStart(long startGameTime) {
        if (!hasFlight() || startGameTime < 0L) {
            throw new IllegalArgumentException("Cannot start this stage flight");
        }
        return withFlight(flightHash, flightBytes, flightDurationMillis, startGameTime);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Instance", instanceId);
        tag.putString("StageId", stageId);
        tag.putString("LodPack", lodPackId.toString());
        tag.putLong("LodAnchor", lodAnchor.asLong());
        tag.putInt("Slot", slot);
        tag.putInt("Capacity", capacity);
        tag.put("Boundary", boundary.save());
        tag.put("ClientScene", clientScene.save());
        tag.putBoolean("Persistent", persistent);
        tag.putString("InteractionPolicy", interactionPolicy.name());
        if (hasFlight()) {
            tag.putString("FlightHash", flightHash);
            tag.putInt("FlightBytes", flightBytes);
            tag.putLong("FlightDuration", flightDurationMillis);
            tag.putLong("FlightStart", flightStartGameTime);
        }
        return tag;
    }

    public static StageInstance load(CompoundTag tag) {
        return new StageInstance(tag.getUUID("Instance"), tag.getString("StageId"),
                new ResourceLocation(tag.getString("LodPack")), BlockPos.of(tag.getLong("LodAnchor")),
                tag.getInt("Slot"), tag.getInt("Capacity"),
                tag.contains("Boundary", Tag.TAG_COMPOUND)
                        ? StageBoundary.load(tag.getCompound("Boundary")) : StageBoundary.defaults(),
                tag.contains("ClientScene", Tag.TAG_COMPOUND)
                        ? StageClientScene.load(tag.getCompound("ClientScene"))
                        : StageClientScene.defaults(0L, 0L),
                tag.getBoolean("Persistent"), tag.contains("InteractionPolicy", Tag.TAG_STRING)
                        ? StageTemplate.InteractionPolicy.valueOf(tag.getString("InteractionPolicy"))
                        : StageTemplate.InteractionPolicy.ADVENTURE,
                tag.getString("FlightHash"), tag.getInt("FlightBytes"),
                tag.getLong("FlightDuration"), tag.contains("FlightStart") ? tag.getLong("FlightStart") : -1L);
    }

    private StageInstance copy(String newStageId, ResourceLocation pack, BlockPos anchor, int newCapacity,
                               StageBoundary newBoundary, StageClientScene scene, boolean newPersistent,
                               StageTemplate.InteractionPolicy newInteractionPolicy,
                               String hash, int bytes, long durationMillis, long startGameTime) {
        return new StageInstance(instanceId, newStageId, pack, anchor, slot, newCapacity, newBoundary, scene,
                newPersistent, newInteractionPolicy, hash, bytes, durationMillis, startGameTime);
    }
}
