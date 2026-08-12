package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;

import javax.annotation.Nullable;
import java.nio.file.Path;

/**
 * Minimal shared session state for the live Voxy backdrop. Set by the server
 * when {@code /dynamicstage start <stage> dim x y z} runs; read by the client to
 * know which anchor and Voxy storage to render on entering the stage dimension.
 */
public final class StageSession {

    /** LOD data source: "voxy" (RocksDB) or "dh" (Distant Horizons sqlite). */
    public static final String SOURCE_VOXY = "voxy";
    public static final String SOURCE_DH = "dh";

    @Nullable
    public static volatile BlockPos anchor;
    @Nullable
    public static volatile String stageId;
    @Nullable
    public static volatile Path dataFile;
    @Nullable
    public static volatile String source;

    private StageSession() {
    }

    public static void set(@Nullable BlockPos anchor, @Nullable String stageId,
                           @Nullable Path dataFile, @Nullable String source) {
        StageSession.anchor = anchor;
        StageSession.stageId = stageId;
        StageSession.dataFile = dataFile;
        StageSession.source = source;
    }

    public static void reset() {
        anchor = null;
        stageId = null;
        dataFile = null;
        source = null;
    }
}
