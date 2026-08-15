package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import vibe.liteming.dynamicstage.flight.StageFlightCodec;
import vibe.liteming.dynamicstage.util.ContentHash;

import java.util.UUID;

/** Persisted membership and backdrop binding for one player in a stage instance. */
public record StageSession(
        UUID playerId,
        UUID instanceId,
        String stageId,
        ResourceLocation lodPackId,
        BlockPos lodAnchor,
        int slot,
        int capacity,
        StageBoundary boundary,
        StageClientScene clientScene,
        ResourceKey<Level> returnDimension,
        Vec3 returnPosition,
        float returnYRot,
        float returnXRot,
        String flightHash,
        int flightBytes,
        long flightDurationMillis,
        long flightStartGameTime
) {

    public static final int MAX_CAPACITY = 64;

    public StageSession {
        if (playerId == null || instanceId == null || lodPackId == null || lodAnchor == null || boundary == null
                || clientScene == null
                || returnDimension == null || returnPosition == null) {
            throw new IllegalArgumentException("Stage session contains null identity or position state");
        }
        if (stageId == null || stageId.isBlank() || stageId.length() > 128) {
            throw new IllegalArgumentException("Invalid stage id");
        }
        if (slot < 0 || slot >= StagePlacement.MAX_SLOTS) {
            throw new IllegalArgumentException("Invalid stage slot: " + slot);
        }
        if (capacity < 1 || capacity > MAX_CAPACITY) {
            throw new IllegalArgumentException("Invalid stage capacity: " + capacity);
        }
        validateFlight(flightHash, flightBytes, flightDurationMillis, flightStartGameTime);
    }

    public BlockPos stageOrigin() {
        return StagePlacement.originForSlot(slot);
    }

    public boolean hasFlight() {
        return !flightHash.isEmpty();
    }

    static void validateFlight(String hash, int bytes, long durationMillis, long startGameTime) {
        if (hash == null) {
            throw new IllegalArgumentException("Flight hash cannot be null");
        }
        if (hash.isEmpty()) {
            if (bytes != 0 || durationMillis != 0L || startGameTime != -1L) {
                throw new IllegalArgumentException("Empty flight contains playback state");
            }
            return;
        }
        if (!ContentHash.isSha256(hash)) {
            throw new IllegalArgumentException("Invalid flight hash");
        }
        if (bytes <= 0 || bytes > StageFlightCodec.MAX_BYTES) {
            throw new IllegalArgumentException("Invalid flight size: " + bytes);
        }
        if (durationMillis < StageFlightCodec.MIN_DURATION_MILLIS
                || durationMillis > StageFlightCodec.MAX_DURATION_MILLIS) {
            throw new IllegalArgumentException("Invalid flight duration: " + durationMillis);
        }
        if (startGameTime < -1L) {
            throw new IllegalArgumentException("Invalid flight start game time");
        }
    }

    public StageSession withFlightStart(long startGameTime) {
        if (!hasFlight() || startGameTime < 0L) {
            throw new IllegalArgumentException("Cannot start this stage flight");
        }
        return copy(lodAnchor, boundary, startGameTime);
    }

    public StageSession withLodAnchor(BlockPos anchor) {
        return copy(anchor, boundary, flightStartGameTime);
    }

    public StageSession withLodPack(ResourceLocation pack) {
        return new StageSession(playerId, instanceId, stageId, pack, lodAnchor, slot, capacity, boundary,
                clientScene, returnDimension, returnPosition, returnYRot, returnXRot,
                flightHash, flightBytes, flightDurationMillis, flightStartGameTime);
    }

    public StageSession withTemplateSettings(String newStageId, ResourceLocation pack, BlockPos anchor,
                                             int newCapacity, StageBoundary newBoundary,
                                             StageClientScene scene) {
        return new StageSession(playerId, instanceId, newStageId, pack, anchor, slot, newCapacity,
                newBoundary, scene, returnDimension, returnPosition, returnYRot, returnXRot,
                flightHash, flightBytes, flightDurationMillis, flightStartGameTime);
    }

    public StageSession withFlight(String hash, int bytes, long durationMillis, long startGameTime) {
        return new StageSession(playerId, instanceId, stageId, lodPackId, lodAnchor, slot, capacity, boundary,
                clientScene, returnDimension, returnPosition, returnYRot, returnXRot,
                hash, bytes, durationMillis, startGameTime);
    }

    public StageSession withoutFlight() {
        return withFlight("", 0, 0L, -1L);
    }

    public StageSession withBoundary(StageBoundary newBoundary) {
        return copy(lodAnchor, newBoundary, flightStartGameTime);
    }

    public StageSession withClientScene(StageClientScene scene) {
        return new StageSession(playerId, instanceId, stageId, lodPackId, lodAnchor, slot, capacity, boundary, scene,
                returnDimension, returnPosition, returnYRot, returnXRot,
                flightHash, flightBytes, flightDurationMillis, flightStartGameTime);
    }

    private StageSession copy(BlockPos anchor, StageBoundary newBoundary, long flightStart) {
        return new StageSession(playerId, instanceId, stageId, lodPackId, anchor, slot, capacity, newBoundary,
                clientScene,
                returnDimension, returnPosition, returnYRot, returnXRot,
                flightHash, flightBytes, flightDurationMillis, flightStart);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Player", playerId);
        tag.putUUID("Instance", instanceId);
        tag.putString("StageId", stageId);
        tag.putString("LodPack", lodPackId.toString());
        tag.putLong("LodAnchor", lodAnchor.asLong());
        tag.putInt("Slot", slot);
        tag.putInt("Capacity", capacity);
        tag.put("Boundary", boundary.save());
        tag.put("ClientScene", clientScene.save());
        tag.putString("ReturnDimension", returnDimension.location().toString());
        tag.putDouble("ReturnX", returnPosition.x);
        tag.putDouble("ReturnY", returnPosition.y);
        tag.putDouble("ReturnZ", returnPosition.z);
        tag.putFloat("ReturnYRot", returnYRot);
        tag.putFloat("ReturnXRot", returnXRot);
        if (hasFlight()) {
            tag.putString("FlightHash", flightHash);
            tag.putInt("FlightBytes", flightBytes);
            tag.putLong("FlightDuration", flightDurationMillis);
            tag.putLong("FlightStart", flightStartGameTime);
        }
        return tag;
    }

    public static StageSession load(CompoundTag tag) {
        ResourceLocation dimensionId = new ResourceLocation(tag.getString("ReturnDimension"));
        ResourceKey<Level> returnDimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        return new StageSession(
                tag.getUUID("Player"),
                tag.getUUID("Instance"),
                tag.getString("StageId"),
                new ResourceLocation(tag.getString("LodPack")),
                BlockPos.of(tag.getLong("LodAnchor")),
                tag.getInt("Slot"),
                tag.getInt("Capacity"),
                tag.contains("Boundary", Tag.TAG_COMPOUND)
                        ? StageBoundary.load(tag.getCompound("Boundary"))
                        : StageBoundary.defaults(),
                tag.contains("ClientScene", Tag.TAG_COMPOUND)
                        ? StageClientScene.load(tag.getCompound("ClientScene"))
                        : StageClientScene.defaults(0L, 0L),
                returnDimension,
                new Vec3(tag.getDouble("ReturnX"), tag.getDouble("ReturnY"), tag.getDouble("ReturnZ")),
                tag.getFloat("ReturnYRot"),
                tag.getFloat("ReturnXRot"),
                tag.getString("FlightHash"),
                tag.getInt("FlightBytes"),
                tag.getLong("FlightDuration"),
                tag.contains("FlightStart") ? tag.getLong("FlightStart") : -1L
        );
    }
}
