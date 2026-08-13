package vibe.liteming.dynamicstage.stage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** World-persisted per-player stage sessions. */
public final class StageSessionData extends SavedData {

    private static final Logger LOGGER = LoggerFactory.getLogger(StageSessionData.class);
    private static final String NAME = "dynamicstage_sessions";
    private final Map<UUID, StageSession> sessions = new HashMap<>();

    public static StageSessionData get(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(StageSessionData::load, StageSessionData::new, NAME);
    }

    public static StageSessionData load(CompoundTag tag) {
        StageSessionData data = new StageSessionData();
        ListTag entries = tag.getList("Sessions", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            try {
                StageSession session = StageSession.load(entries.getCompound(i));
                data.sessions.put(session.playerId(), session);
            } catch (RuntimeException e) {
                LOGGER.warn("Ignoring invalid persisted stage session: {}", e.getMessage());
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
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

    public Optional<StageSession> findInstance(UUID instanceId) {
        return sessions.values().stream().filter(session -> session.instanceId().equals(instanceId)).findFirst();
    }

    public List<StageSession> members(UUID instanceId) {
        return sessions.values().stream().filter(session -> session.instanceId().equals(instanceId)).toList();
    }

    public void put(StageSession session) {
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
        if (changed) {
            setDirty();
        }
    }

    public Optional<StageSession> remove(UUID playerId) {
        StageSession removed = sessions.remove(playerId);
        if (removed != null) {
            setDirty();
        }
        return Optional.ofNullable(removed);
    }
}
