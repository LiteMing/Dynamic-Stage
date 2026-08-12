package vibe.liteming.dynamicstage.bake.voxy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Resolves Voxy {@code id_mappings} (blockId → {@link BlockState}) from a
 * database. Shared by the bake provider and the direct renderer.
 */
public final class VoxyMappings {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyMappings.class);

    private VoxyMappings() {
    }

    /** Loads all block-state mappings from the DB (blockId → state). */
    public static Map<Integer, BlockState> load(VoxyRocksDB db) {
        Map<Integer, BlockState> out = new HashMap<>();
        for (Map.Entry<Integer, byte[]> entry : db.getIdMappings().entrySet()) {
            int key = entry.getKey();
            int type = key >>> 30;
            int id = key & ((1 << 30) - 1);
            if (type == VoxyRocksDB.BLOCK_STATE_TYPE) {
                BlockState state = parseBlockState(entry.getValue());
                if (state != null) {
                    out.put(id, state);
                }
            }
        }
        LOGGER.info("Voxy: loaded {} block mappings", out.size());
        return out;
    }

    @Nullable
    private static BlockState parseBlockState(byte[] gzipNbt) {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(new ByteArrayInputStream(gzipNbt))))) {
            CompoundTag tag = NbtIo.read(in, new NbtAccounter(0x40000000L));
            if (tag == null || !tag.contains("block_state")) {
                return null;
            }
            CompoundTag bs = tag.getCompound("block_state");
            String name = bs.getString("Name");
            ResourceLocation id = ResourceLocation.tryParse(name);
            if (id == null) {
                return null;
            }
            Block block = ForgeRegistries.BLOCKS.getValue(id);
            return block == null ? null : block.defaultBlockState();
        } catch (IOException e) {
            return null;
        }
    }
}
