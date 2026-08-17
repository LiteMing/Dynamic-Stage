package vibe.liteming.dynamicstage.event;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.stage.StageSession;

import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Server-side extension point for experimental client-detected Voxy LOD collisions. */
public final class StageLodCollisionEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(StageLodCollisionEvents.class);
    private static final CopyOnWriteArrayList<Consumer<Event>> LISTENERS = new CopyOnWriteArrayList<>();

    private StageLodCollisionEvents() {
    }

    /** Registers a listener and returns a handle that removes it when closed. */
    public static Registration register(Consumer<Event> listener) {
        Consumer<Event> checked = Objects.requireNonNull(listener, "listener");
        LISTENERS.add(checked);
        return () -> LISTENERS.remove(checked);
    }

    public static void post(ServerPlayer player, StageSession session, Direction direction,
                            float penetration, long gameTime) {
        Event event = new Event(player, session, direction, penetration, gameTime);
        for (Consumer<Event> listener : LISTENERS) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                LOGGER.error("Dynamic Stage LOD collision listener failed", e);
            }
        }
    }

    public record Event(ServerPlayer player, StageSession session, Direction direction,
                        float penetration, long gameTime) {
        public Event {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(session, "session");
            Objects.requireNonNull(direction, "direction");
        }
    }

    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }
}
