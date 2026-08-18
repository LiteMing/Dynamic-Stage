package vibe.liteming.dynamicstage.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.stage.StageClientScene;
import vibe.liteming.dynamicstage.lod.LodPackageOffer;

import java.util.UUID;

/** S2C stage membership and client-side native LOD binding. */
public record StageSessionPacket(boolean active, UUID instanceId, String stageId, ResourceLocation lodPackId,
                                 BlockPos lodAnchor, BlockPos stageOrigin, int capacity,
                                 StageBoundary boundary,
                                 StageClientScene clientScene,
                                 String flightHash, int flightBytes, long flightDurationMillis,
                                 LodPackageOffer lodOffer) {

    public StageSessionPacket(boolean active, UUID instanceId, String stageId, ResourceLocation lodPackId,
                              BlockPos lodAnchor, BlockPos stageOrigin, int capacity,
                              StageBoundary boundary, StageClientScene clientScene,
                              String flightHash, int flightBytes, long flightDurationMillis) {
        this(active, instanceId, stageId, lodPackId, lodAnchor, stageOrigin, capacity, boundary, clientScene,
                flightHash, flightBytes, flightDurationMillis, null);
    }

    public static StageSessionPacket active(StageSession session) {
        return active(session, null);
    }

    public static StageSessionPacket active(StageSession session, LodPackageOffer offer) {
        return new StageSessionPacket(true, session.instanceId(), session.stageId(), session.lodPackId(),
                session.lodAnchor(), session.stageOrigin(), session.capacity(), session.boundary(),
                session.clientScene(),
                session.flightHash(), session.flightBytes(), session.flightDurationMillis(), offer);
    }

    public static StageSessionPacket clear() {
        return new StageSessionPacket(false, new UUID(0L, 0L), "",
                new ResourceLocation("dynamicstage", "none"),
                BlockPos.ZERO, BlockPos.ZERO, 1, StageBoundary.defaults(), StageClientScene.defaults(0L, 0L),
                "", 0, 0L, null);
    }

    public static void encode(StageSessionPacket packet, FriendlyByteBuf buf) {
        buf.writeBoolean(packet.active);
        if (packet.active) {
            buf.writeUUID(packet.instanceId);
            buf.writeUtf(packet.stageId, 128);
            buf.writeResourceLocation(packet.lodPackId);
            buf.writeBlockPos(packet.lodAnchor);
            buf.writeBlockPos(packet.stageOrigin);
            buf.writeVarInt(packet.capacity);
            buf.writeVarInt(packet.boundary.width());
            buf.writeVarInt(packet.boundary.depth());
            buf.writeVarInt(packet.boundary.height());
            buf.writeInt(packet.boundary.color());
            encodeClientScene(packet.clientScene, buf);
            buf.writeUtf(packet.flightHash, 64);
            buf.writeVarInt(packet.flightBytes);
            buf.writeVarLong(packet.flightDurationMillis);
            buf.writeBoolean(packet.lodOffer != null);
            if (packet.lodOffer != null) {
                LodPackageOffer.encode(packet.lodOffer, buf);
            }
        }
    }

    public static StageSessionPacket decode(FriendlyByteBuf buf) {
        if (!buf.readBoolean()) {
            return clear();
        }
        UUID instanceId = buf.readUUID();
        String stageId = buf.readUtf(128);
        ResourceLocation lodPackId = buf.readResourceLocation();
        BlockPos lodAnchor = buf.readBlockPos();
        BlockPos stageOrigin = buf.readBlockPos();
        int capacity = buf.readVarInt();
        StageBoundary boundary = new StageBoundary(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readInt());
        StageClientScene scene = decodeClientScene(buf);
        String flightHash = buf.readUtf(64);
        int flightBytes = buf.readVarInt();
        long flightDurationMillis = buf.readVarLong();
        LodPackageOffer offer = buf.readableBytes() > 0 && buf.readBoolean()
                ? LodPackageOffer.decode(buf) : null;
        return new StageSessionPacket(true, instanceId, stageId, lodPackId, lodAnchor, stageOrigin, capacity,
                boundary, scene, flightHash, flightBytes, flightDurationMillis, offer);
    }

    public static void encodeClientScene(StageClientScene scene, FriendlyByteBuf buf) {
        buf.writeBoolean(scene.followPlayer());
        buf.writeFloat(scene.lodMovementScale());
        buf.writeFloat(scene.dhNearFadeScale());
        buf.writeFloat(scene.voxyNearPlane());
        buf.writeBoolean(scene.voxyNearCulling());
        buf.writeBoolean(scene.lodVisible());
        buf.writeFloat(scene.lodBlurRadius());
        buf.writeEnum(scene.lodTransition());
        buf.writeVarInt(scene.lodTransitionTicks());
        buf.writeLong(scene.lodTransitionStartGameTime());
        buf.writeEnum(scene.timeMode());
        buf.writeLong(scene.timeBaseDayTime());
        buf.writeLong(scene.timeBaseGameTime());
        buf.writeVarLong(scene.timeCycleTicks());
        buf.writeEnum(scene.skyMode());
    }

    public static StageClientScene decodeClientScene(FriendlyByteBuf buf) {
        return new StageClientScene(buf.readBoolean(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                buf.readBoolean(), buf.readBoolean(), buf.readFloat(),
                buf.readEnum(StageClientScene.Transition.class), buf.readVarInt(), buf.readLong(),
                buf.readEnum(StageClientScene.TimeMode.class), buf.readLong(), buf.readLong(), buf.readVarLong(),
                buf.readEnum(StageClientScene.SkyMode.class));
    }

}
