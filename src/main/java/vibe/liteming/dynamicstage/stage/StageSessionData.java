package vibe.liteming.dynamicstage.stage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
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

    public void put(StageSession session) {
        sessions.put(session.playerId(), session);
        setDirty();
    }

    public Optional<StageSession> remove(UUID playerId) {
        StageSession removed = sessions.remove(playerId);
        if (removed != null) {
            setDirty();
        }
        return Optional.ofNullable(removed);
    }
}
