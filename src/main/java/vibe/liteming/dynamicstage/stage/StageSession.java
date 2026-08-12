package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import vibe.liteming.dynamicstage.backdrop.BackdropProducts;

import java.util.UUID;

/** Persisted state for one player currently assigned to an isolated stage region. */
public record StageSession(
        UUID playerId,
        String stageId,
        String source,
        BlockPos sourceAnchor,
        int slot,
        ResourceKey<Level> returnDimension,
        Vec3 returnPosition,
        float returnYRot,
        float returnXRot,
        String backdropHash,
        long backdropBytes
) {

    public static final String SOURCE_VOXY = "voxy";
    public static final String SOURCE_DH = "dh";

    public StageSession {
        if (playerId == null || sourceAnchor == null || returnDimension == null || returnPosition == null) {
            throw new IllegalArgumentException("Stage session contains null identity or position state");
        }
        if (stageId == null || stageId.isBlank() || stageId.length() > 128) {
            throw new IllegalArgumentException("Invalid stage id");
        }
        if (!SOURCE_VOXY.equals(source) && !SOURCE_DH.equals(source)) {
            throw new IllegalArgumentException("Invalid LOD source: " + source);
        }
        if (slot < 0 || slot >= StagePlacement.MAX_SLOTS) {
            throw new IllegalArgumentException("Invalid stage slot: " + slot);
        }
        if (!BackdropProducts.isSha256(backdropHash)) {
            throw new IllegalArgumentException("Invalid backdrop hash");
        }
        if (backdropBytes <= 0 || backdropBytes > BackdropProducts.MAX_PRODUCT_BYTES) {
            throw new IllegalArgumentException("Invalid backdrop size: " + backdropBytes);
        }
    }

    public BlockPos stageOrigin() {
        return StagePlacement.originForSlot(slot);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Player", playerId);
        tag.putString("StageId", stageId);
        tag.putString("Source", source);
        tag.putLong("SourceAnchor", sourceAnchor.asLong());
        tag.putInt("Slot", slot);
        tag.putString("ReturnDimension", returnDimension.location().toString());
        tag.putDouble("ReturnX", returnPosition.x);
        tag.putDouble("ReturnY", returnPosition.y);
        tag.putDouble("ReturnZ", returnPosition.z);
        tag.putFloat("ReturnYRot", returnYRot);
        tag.putFloat("ReturnXRot", returnXRot);
        tag.putString("BackdropHash", backdropHash);
        tag.putLong("BackdropBytes", backdropBytes);
        return tag;
    }

    public static StageSession load(CompoundTag tag) {
        ResourceLocation dimensionId = ResourceLocation.parse(tag.getString("ReturnDimension"));
        ResourceKey<Level> returnDimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
        return new StageSession(
                tag.getUUID("Player"),
                tag.getString("StageId"),
                tag.getString("Source"),
                BlockPos.of(tag.getLong("SourceAnchor")),
                tag.getInt("Slot"),
                returnDimension,
                new Vec3(tag.getDouble("ReturnX"), tag.getDouble("ReturnY"), tag.getDouble("ReturnZ")),
                tag.getFloat("ReturnYRot"),
                tag.getFloat("ReturnXRot"),
                tag.getString("BackdropHash"),
                tag.getLong("BackdropBytes")
        );
    }
}
