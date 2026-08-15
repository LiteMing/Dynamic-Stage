package vibe.liteming.dynamicstage.stage;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoquatArenaCompatTest {
    @Test
    void onlyActivatesForSnapshotsContainingLoquatAreas() {
        CompoundTag snapshot = new CompoundTag();
        assertFalse(LoquatArenaCompat.hasAreas(snapshot));

        CompoundTag loquat = new CompoundTag();
        loquat.put("Areas", new ListTag());
        snapshot.put("Loquat", loquat);
        assertFalse(LoquatArenaCompat.hasAreas(snapshot));

        loquat.getList("Areas", Tag.TAG_COMPOUND).add(new CompoundTag());
        assertTrue(LoquatArenaCompat.hasAreas(snapshot));
    }
}
