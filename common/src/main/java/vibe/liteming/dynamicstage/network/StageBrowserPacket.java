package vibe.liteming.dynamicstage.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bounded player-facing protocol for browsing and joining active stage instances. */
public final class StageBrowserPacket {
    public static final int MAX_INSTANCES = 4096;
    public static final UUID NO_INSTANCE = new UUID(0L, 0L);

    private StageBrowserPacket() {
    }

    public record Request(Action action, UUID instanceId) {
        public Request {
            if (action == null || instanceId == null) {
                throw new IllegalArgumentException("Invalid stage browser request");
            }
        }

        public static Request refresh() {
            return new Request(Action.REFRESH, NO_INSTANCE);
        }
    }

    public record Instance(UUID instanceId, String stageId, int slot, int members,
                           int capacity, boolean available) {
        public Instance {
            if (instanceId == null || stageId == null || stageId.isBlank() || stageId.length() > 128
                    || slot < 0 || slot >= MAX_INSTANCES || members < 0 || capacity < 1
                    || members > capacity) {
                throw new IllegalArgumentException("Invalid stage browser instance");
            }
        }

        public String label() {
            return vibe.liteming.dynamicstage.stage.StageInstanceRef.label(slot, stageId);
        }
    }

    public record State(List<Instance> instances, String message, boolean error) {
        public State {
            if (instances == null || instances.size() > MAX_INSTANCES || message == null
                    || message.length() > 256) {
                throw new IllegalArgumentException("Invalid stage browser state");
            }
            instances = List.copyOf(instances);
        }
    }

    public enum Action { REFRESH, JOIN }

    public static void encodeRequest(Request packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.action());
        buf.writeUUID(packet.instanceId());
    }

    public static Request decodeRequest(FriendlyByteBuf buf) {
        return new Request(buf.readEnum(Action.class), buf.readUUID());
    }

    public static void encodeState(State packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.instances().size());
        for (Instance instance : packet.instances()) {
            buf.writeUUID(instance.instanceId());
            buf.writeUtf(instance.stageId(), 128);
            buf.writeVarInt(instance.slot());
            buf.writeVarInt(instance.members());
            buf.writeVarInt(instance.capacity());
            buf.writeBoolean(instance.available());
        }
        buf.writeUtf(packet.message(), 256);
        buf.writeBoolean(packet.error());
    }

    public static State decodeState(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_INSTANCES) {
            throw new IllegalArgumentException("Invalid stage browser instance count: " + count);
        }
        List<Instance> instances = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            instances.add(new Instance(buf.readUUID(), buf.readUtf(128), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
        }
        return new State(instances, buf.readUtf(256), buf.readBoolean());
    }
}
