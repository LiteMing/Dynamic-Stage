package vibe.liteming.dynamicstage.stage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** World-persisted stage instances and their current player memberships. */
public final class StageSessionData extends SavedData {

    private static final Logger LOGGER = LoggerFactory.getLogger(StageSessionData.class);
    private static final String NAME = "dynamicstage_sessions";
    private final Map<UUID, StageSession> sessions = new HashMap<>();
    private final Map<UUID, StageInstance> instances = new HashMap<>();

    public static StageSessionData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(StageSessionData::load, StageSessionData::new, NAME);
    }

    public static StageSessionData load(CompoundTag tag) {
        StageSessionData data = new StageSessionData();
        ListTag instanceEntries = tag.getList("Instances", Tag.TAG_COMPOUND);
        for (int i = 0; i < instanceEntries.size(); i++) {
            try {
                StageInstance instance = StageInstance.load(instanceEntries.getCompound(i));
                data.instances.put(instance.instanceId(), instance);
            } catch (RuntimeException e) {
                LOGGER.warn("Ignoring invalid persisted stage instance: {}", e.getMessage());
            }
        }
        ListTag entries = tag.getList("Sessions", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            try {
                StageSession session = StageSession.load(entries.getCompound(i));
                data.sessions.put(session.playerId(), session);
                // Saves written before instances were separated contain only memberships.
                data.instances.putIfAbsent(session.instanceId(), StageInstance.from(session, false));
            } catch (RuntimeException e) {
                LOGGER.warn("Ignoring invalid persisted stage session: {}", e.getMessage());
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag instanceEntries = new ListTag();
        instances.values().forEach(instance -> instanceEntries.add(instance.save()));
        tag.put("Instances", instanceEntries);
        ListTag entries = new ListTag();
        sessions.values().forEach(session -> entries.add(session.save()));
        tag.put("Sessions", entries);
        return tag;
    }

    public Optional<StageSession> get(UUID playerId) {
        return Optional.ofNullable(sessions.get(playerId));
    }

    public Collection<StageSession> all() {
        return sessions.values();
    }

    public Collection<StageInstance> instances() {
        return instances.values();
    }

    public Optional<StageInstance> findInstance(UUID instanceId) {
        return Optional.ofNullable(instances.get(instanceId));
    }

    public Optional<StageInstance> findRegion(double x, double z) {
        return instances.values().stream()
                .filter(instance -> StagePlacement.containsRegion(instance.stageOrigin(), x, z))
                .findFirst();
    }

    public List<StageSession> members(UUID instanceId) {
        return sessions.values().stream().filter(session -> session.instanceId().equals(instanceId)).toList();
    }

    public void put(StageSession session, boolean persistent) {
        StageInstance existing = instances.get(session.instanceId());
        if (existing == null) {
            instances.put(session.instanceId(), StageInstance.from(session, persistent));
        } else if (existing.persistent() != persistent) {
            instances.put(session.instanceId(), StageInstance.from(session, persistent));
        }
        sessions.put(session.playerId(), session);
        setDirty();
    }

    public void updateInstanceAnchor(UUID instanceId, net.minecraft.core.BlockPos anchor) {
        boolean changed = false;
        for (StageSession session : List.copyOf(sessions.values())) {
            if (session.instanceId().equals(instanceId)) {
                sessions.put(session.playerId(), session.withLodAnchor(anchor));
                changed = true;
            }
        }
        StageInstance instance = instances.get(instanceId);
        if (instance != null) {
            instances.put(instanceId, instance.withLodAnchor(anchor));
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public void updateInstanceLodPack(UUID instanceId, ResourceLocation lodPackId) {
        boolean changed = false;
        for (StageSession session : List.copyOf(sessions.values())) {
            if (session.instanceId().equals(instanceId)) {
                sessions.put(session.playerId(), session.withLodPack(lodPackId));
                changed = true;
            }
        }
        StageInstance instance = instances.get(instanceId);
        if (instance != null) {
            instances.put(instanceId, instance.withLodPack(lodPackId));
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public void updateInstanceTemplate(UUID instanceId, String stageId, ResourceLocation lodPackId,
                                       BlockPos lodAnchor, int capacity, StageBoundary boundary,
                                       StageClientScene scene, String flightHash, int flightBytes,
                                       long flightDurationMillis, long flightStartGameTime,
                                       boolean persistent) {
        boolean changed = false;
        for (StageSession session : List.copyOf(sessions.values())) {
            if (session.instanceId().equals(instanceId)) {
                StageSession updated = session.withTemplateSettings(stageId, lodPackId, lodAnchor,
                        capacity, boundary, scene).withFlight(flightHash, flightBytes,
                        flightDurationMillis, flightStartGameTime);
                sessions.put(session.playerId(), updated);
                changed = true;
            }
        }
        StageInstance instance = instances.get(instanceId);
        if (instance != null) {
            instances.put(instanceId, instance.withTemplateSettings(stageId, lodPackId, lodAnchor,
                    capacity, boundary, scene, persistent).withFlight(flightHash, flightBytes,
                    flightDurationMillis, flightStartGameTime));
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public void updateInstanceBoundary(UUID instanceId, StageBoundary boundary) {
        boolean changed = false;
        for (StageSession session : List.copyOf(sessions.values())) {
            if (session.instanceId().equals(instanceId)) {
                sessions.put(session.playerId(), session.withBoundary(boundary));
                changed = true;
            }
        }
        StageInstance instance = instances.get(instanceId);
        if (instance != null) {
            instances.put(instanceId, instance.withBoundary(boundary));
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public void updateInstanceClientScene(UUID instanceId, StageClientScene scene) {
        boolean changed = false;
        for (StageSession session : List.copyOf(sessions.values())) {
            if (session.instanceId().equals(instanceId)) {
                sessions.put(session.playerId(), session.withClientScene(scene));
                changed = true;
            }
        }
        StageInstance instance = instances.get(instanceId);
        if (instance != null) {
            instances.put(instanceId, instance.withClientScene(scene));
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public void updateInstanceFlightStart(UUID instanceId, long startGameTime) {
        boolean changed = false;
        for (StageSession session : List.copyOf(sessions.values())) {
            if (session.instanceId().equals(instanceId) && session.hasFlight()) {
                sessions.put(session.playerId(), session.withFlightStart(startGameTime));
                changed = true;
            }
        }
        StageInstance instance = instances.get(instanceId);
        if (instance != null && instance.hasFlight()) {
            instances.put(instanceId, instance.withFlightStart(startGameTime));
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public void updateInstanceFlight(UUID instanceId, String hash, int bytes,
                                     long durationMillis, long startGameTime) {
        boolean changed = false;
        for (StageSession session : List.copyOf(sessions.values())) {
            if (session.instanceId().equals(instanceId)) {
                sessions.put(session.playerId(), session.withFlight(hash, bytes, durationMillis, startGameTime));
                changed = true;
            }
        }
        StageInstance instance = instances.get(instanceId);
        if (instance != null) {
            instances.put(instanceId, instance.withFlight(hash, bytes, durationMillis, startGameTime));
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public Optional<StageSession> remove(UUID playerId) {
        StageSession removed = sessions.remove(playerId);
        if (removed != null) {
            StageInstance instance = instances.get(removed.instanceId());
            boolean hasMembers = sessions.values().stream()
                    .anyMatch(session -> session.instanceId().equals(removed.instanceId()));
            if (!hasMembers && instance != null && !instance.persistent()) {
                instances.remove(removed.instanceId());
            }
            setDirty();
        }
        return Optional.ofNullable(removed);
    }
}
