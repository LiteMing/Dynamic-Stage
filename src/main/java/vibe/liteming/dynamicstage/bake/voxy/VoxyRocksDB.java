package vibe.liteming.dynamicstage.bake.voxy;

import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.DBOptions;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Read-only RocksDB access to a Voxy world storage directory (VoxyFileProvider).
 * <p>
 * Matches the m3t4f1v3/voxy mc_1201 fork layout: two column families —
 * {@code world_sections} (key = byte-swapped section key, value = ZSTD-compressed
 * section payload) and {@code id_mappings} (key = type<<30|id, value = gzipped
 * NBT {@code {id, block_state}}).
 */
public final class VoxyRocksDB {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyRocksDB.class);

    public static final byte[] WORLD_SECTIONS_CF = "world_sections".getBytes(StandardCharsets.UTF_8);
    public static final byte[] ID_MAPPINGS_CF = "id_mappings".getBytes(StandardCharsets.UTF_8);
    public static final int BLOCK_STATE_TYPE = 1;
    public static final int BIOME_TYPE = 2;

    @Nullable
    private final RocksDB db;
    @Nullable
    private final ColumnFamilyHandle sectionsHandle;
    @Nullable
    private final ColumnFamilyHandle mappingsHandle;
    private final DBOptions options;
    private final ColumnFamilyOptions cfOptions;
    private final boolean open;

    private VoxyRocksDB(@Nullable RocksDB db, @Nullable ColumnFamilyHandle sections,
                        @Nullable ColumnFamilyHandle mappings, boolean open,
                        DBOptions options, ColumnFamilyOptions cfOptions) {
        this.db = db;
        this.sectionsHandle = sections;
        this.mappingsHandle = mappings;
        this.open = open;
        this.options = options;
        this.cfOptions = cfOptions;
    }

    public boolean isOpen() {
        return open;
    }

    /** Opens a Voxy RocksDB database read-only. Returns null on failure. */
    @Nullable
    public static VoxyRocksDB open(java.nio.file.Path storageDir) {
        if (!java.nio.file.Files.exists(storageDir.resolve("CURRENT"))) {
            LOGGER.warn("Voxy storage not a RocksDB database: {}", storageDir);
            return null;
        }
        LOGGER.info("Voxy: opening RocksDB at {}", storageDir);
        // NOTE: options/cfOpts must OUTLIVE the DB handle — RocksDB native code
        // reads them while the DB is open. Closing them early causes SIGSEGV on
        // later gets/iterations. They are released together with the DB in close().
        var options = new DBOptions().setCreateIfMissing(false);
        var cfOpts = new ColumnFamilyOptions();
        try {
            LOGGER.info("Voxy: openReadOnly...");
            List<ColumnFamilyDescriptor> descriptors = Arrays.asList(
                    new ColumnFamilyDescriptor(RocksDB.DEFAULT_COLUMN_FAMILY, cfOpts),
                    new ColumnFamilyDescriptor(WORLD_SECTIONS_CF, cfOpts),
                    new ColumnFamilyDescriptor(ID_MAPPINGS_CF, cfOpts)
            );
            List<ColumnFamilyHandle> handles = new ArrayList<>();
            RocksDB db = RocksDB.openReadOnly(options, storageDir.toString(), descriptors, handles);
            LOGGER.info("Voxy: openReadOnly OK, {} handles", handles.size());
            return new VoxyRocksDB(db, handles.get(1), handles.get(2), true, options, cfOpts);
        } catch (RocksDBException e) {
            LOGGER.warn("Failed to open Voxy RocksDB at {}: {}", storageDir, e.getMessage());
            options.close();
            cfOpts.close();
            return null;
        } catch (Throwable t) {
            LOGGER.error("Voxy: open crashed", t);
            try {
                options.close();
            } catch (Throwable ignored) {
            }
            try {
                cfOpts.close();
            } catch (Throwable ignored) {
            }
            return null;
        }
    }

    /**
     * Reads a section's compressed payload by section key.
     *
     * @return raw (still ZSTD-compressed) bytes, or null when absent
     */
    @Nullable
    public byte[] getSection(long sectionKey) {
        if (!open || db == null || sectionsHandle == null) {
            return null;
        }
        byte[] key = new byte[8];
        long reversed = Long.reverseBytes(sectionKey);
        for (int i = 0; i < 8; i++) {
            key[i] = (byte) (reversed >>> (i * 8));
        }
        try {
            return db.get(sectionsHandle, key);
        } catch (RocksDBException e) {
            LOGGER.warn("Failed to read Voxy section {}: {}", sectionKey, e.getMessage());
            return null;
        }
    }

    /** Returns all id_mappings as int key → raw value bytes. */
    public java.util.Map<Integer, byte[]> getIdMappings() {
        java.util.Map<Integer, byte[]> out = new java.util.HashMap<>();
        if (!open || db == null || mappingsHandle == null) {
            return out;
        }
        try (var iterator = db.newIterator(mappingsHandle)) {
            iterator.seekToFirst();
            while (iterator.isValid()) {
                byte[] k = iterator.key();
                if (k.length == 4) {
                    int key = ((k[0] & 0xFF) << 24) | ((k[1] & 0xFF) << 16)
                            | ((k[2] & 0xFF) << 8) | (k[3] & 0xFF);
                    out.put(key, iterator.value());
                }
                iterator.next();
            }
        }
        return out;
    }

    /**
     * Iterates all section keys (optionally filtered by level).
     */
    public void iterateSections(int level, java.util.function.LongConsumer consumer) {
        iterateSectionsWhile(level, key -> {
            consumer.accept(key);
            return true;
        });
    }

    /** Iterates section keys until the predicate returns false. */
    public void iterateSectionsWhile(int level, java.util.function.LongPredicate consumer) {
        if (!open || db == null || sectionsHandle == null) {
            return;
        }
        try (var iterator = db.newIterator(sectionsHandle)) {
            if (level >= 0) {
                long seekValue = Long.reverseBytes((long) level << 60);
                byte[] seek = new byte[8];
                for (int i = 0; i < 8; i++) {
                    seek[i] = (byte) (seekValue >>> (i * 8));
                }
                iterator.seek(seek);
            } else {
                iterator.seekToFirst();
            }
            while (iterator.isValid()) {
                byte[] k = iterator.key();
                if (k.length == 8) {
                    long reversed = ByteBuffer.wrap(k).order(ByteOrder.LITTLE_ENDIAN).getLong();
                    long key = Long.reverseBytes(reversed);
                    if (level >= 0 && VoxySectionKey.levelOf(key) != level) {
                        break;
                    }
                    if (!consumer.test(key)) {
                        break;
                    }
                }
                iterator.next();
            }
        }
    }

    public void close() {
        if (db != null) {
            if (sectionsHandle != null) {
                sectionsHandle.close();
            }
            if (mappingsHandle != null) {
                mappingsHandle.close();
            }
            db.close();
        }
        options.close();
        cfOptions.close();
    }
}
