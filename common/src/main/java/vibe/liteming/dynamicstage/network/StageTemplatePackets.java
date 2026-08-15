package vibe.liteming.dynamicstage.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.stage.StageBoundary;
import vibe.liteming.dynamicstage.template.StageTemplate;
import vibe.liteming.dynamicstage.template.StageTemplateSummary;

import java.util.ArrayList;
import java.util.List;

/** Bounded editor protocol that deliberately excludes arena and flight payloads. */
public final class StageTemplatePackets {
    public static final int MAX_TEMPLATES = 256;

    private StageTemplatePackets() {
    }

    public record ListPacket(List<StageTemplateSummary> templates) {
        public ListPacket {
            if (templates == null || templates.size() > MAX_TEMPLATES) {
                throw new IllegalArgumentException("Invalid stage template list size");
            }
            templates = List.copyOf(templates);
        }
    }

    public record EditPacket(Action action, StageTemplateSummary template) {
        public EditPacket {
            if (action == null || template == null) {
                throw new IllegalArgumentException("Invalid template edit packet");
            }
        }
    }

    public enum Action { SAVE, SAVE_AND_START, CAPTURE_ACTIVE, RELOAD_ACTIVE, USE_CONFIGURED_FLIGHT, CLEAR_FLIGHT }

    public static void encodeList(ListPacket packet, FriendlyByteBuf buf) {
        buf.writeVarInt(packet.templates.size());
        packet.templates.forEach(template -> encodeSummary(template, buf));
    }

    public static ListPacket decodeList(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_TEMPLATES) {
            throw new IllegalArgumentException("Invalid stage template list size: " + count);
        }
        List<StageTemplateSummary> templates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            templates.add(decodeSummary(buf));
        }
        return new ListPacket(templates);
    }

    public static void encodeEdit(EditPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.action);
        encodeSummary(packet.template, buf);
    }

    public static EditPacket decodeEdit(FriendlyByteBuf buf) {
        return new EditPacket(buf.readEnum(Action.class), decodeSummary(buf));
    }

    private static void encodeSummary(StageTemplateSummary template, FriendlyByteBuf buf) {
        buf.writeUtf(template.id(), 128);
        buf.writeResourceLocation(template.lodPackId());
        buf.writeBlockPos(template.lodAnchor());
        buf.writeVarInt(template.boundary().width());
        buf.writeVarInt(template.boundary().depth());
        buf.writeVarInt(template.boundary().height());
        buf.writeInt(template.boundary().color());
        StageSessionPacket.encodeClientScene(template.clientScene(), buf);
        buf.writeVarInt(template.capacity());
        buf.writeEnum(template.instanceMode());
        buf.writeEnum(template.resetPolicy());
        buf.writeUtf(template.flightName(), 64);
    }

    private static StageTemplateSummary decodeSummary(FriendlyByteBuf buf) {
        String id = buf.readUtf(128);
        ResourceLocation lodPack = buf.readResourceLocation();
        BlockPos anchor = buf.readBlockPos();
        StageBoundary boundary = new StageBoundary(buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readInt());
        return new StageTemplateSummary(id, lodPack, anchor, boundary,
                StageSessionPacket.decodeClientScene(buf), buf.readVarInt(),
                buf.readEnum(StageTemplate.InstanceMode.class),
                buf.readEnum(StageTemplate.ResetPolicy.class), buf.readUtf(64));
    }
}
