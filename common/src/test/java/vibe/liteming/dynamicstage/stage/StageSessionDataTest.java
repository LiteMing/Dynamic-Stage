package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StageSessionDataTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void releasesNonPersistentInstanceAfterItsLastMemberLeaves() {
        StageSessionData data = new StageSessionData();
        StageSession session = session(UUID.randomUUID(), UUID.randomUUID(), 0);

        data.put(session, false);
        data.remove(session.playerId());

        assertTrue(data.findInstance(session.instanceId()).isEmpty());
    }

    @Test
    void retainsAndRoundTripsEmptyPersistentInstance() {
        StageSessionData data = new StageSessionData();
        StageSession session = session(UUID.randomUUID(), UUID.randomUUID(), 1);

        data.put(session, true);
        data.remove(session.playerId());

        StageInstance retained = data.findInstance(session.instanceId()).orElseThrow();
        assertTrue(retained.persistent());
        assertEquals(StagePlacement.REGION_SPACING, retained.stageOrigin().getX());
        assertTrue(data.members(session.instanceId()).isEmpty());

        StageSessionData loaded = StageSessionData.load(data.save(new CompoundTag()));
        StageInstance roundTripped = loaded.findInstance(session.instanceId()).orElseThrow();
        assertTrue(roundTripped.persistent());
        assertEquals(1, roundTripped.slot());
        assertFalse(loaded.get(session.playerId()).isPresent());
    }

    @Test
    void keepsNonPersistentInstanceWhileAnotherMemberIsPending() {
        StageSessionData data = new StageSessionData();
        StageSession session = session(UUID.randomUUID(), UUID.randomUUID(), 2);

        data.put(session, false);
        data.remove(session.playerId(), true);

        StageInstance waiting = data.findInstance(session.instanceId()).orElseThrow();
        assertFalse(waiting.persistent());
        assertTrue(data.members(session.instanceId()).isEmpty());
    }

    @Test
    void updatesAndExplicitlyRemovesAnEmptyInstance() {
        StageSessionData data = new StageSessionData();
        StageSession session = session(UUID.randomUUID(), UUID.randomUUID(), 3);

        data.put(session, true);
        data.remove(session.playerId());
        assertTrue(data.updateInstancePersistent(session.instanceId(), false));
        assertFalse(data.findInstance(session.instanceId()).orElseThrow().persistent());
        assertTrue(data.removeEmptyInstance(session.instanceId()));
        assertTrue(data.findInstance(session.instanceId()).isEmpty());
    }

    @Test
    void observersPersistWithoutConsumingCapacity() {
        StageSessionData data = new StageSessionData();
        StageSession observer = session(UUID.randomUUID(), UUID.randomUUID(), 4, true);

        data.put(observer, true);

        assertEquals(1, data.members(observer.instanceId()).size());
        assertTrue(data.participants(observer.instanceId()).isEmpty());
        StageSession loaded = StageSessionData.load(data.save(new CompoundTag()))
                .get(observer.playerId()).orElseThrow();
        assertTrue(loaded.observer());
        assertEquals(GameType.SURVIVAL, loaded.returnGameMode());
    }

    private static StageSession session(UUID playerId, UUID instanceId, int slot) {
        return session(playerId, instanceId, slot, false);
    }

    private static StageSession session(UUID playerId, UUID instanceId, int slot, boolean observer) {
        return new StageSession(playerId, instanceId, "arena", new ResourceLocation("dynamicstage", "none"),
                BlockPos.ZERO, slot, 4, StageBoundary.defaults(), StageClientScene.defaults(0L, 0L),
                Level.OVERWORLD, Vec3.ZERO, 0.0F, 0.0F, observer, GameType.SURVIVAL,
                "", 0, 0L, -1L);
    }
}
