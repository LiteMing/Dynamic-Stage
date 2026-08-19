package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DhRuntimeSnapshotTest {
    private static final String SQLITE_PREFIX = "jdbc:dh_sqlite:";

    @BeforeAll
    static void loadDhSqlite() throws Exception {
        Class.forName("dh_sqlite.JDBC");
    }

    @Test
    void createsReusableSnapshotWithoutMutatingSource(@TempDir Path temporary) throws Exception {
        Path sourceDatabase = createDatabase(temporary.resolve("source/DistantHorizons.sqlite"), true);
        byte[] sourceBefore = Files.readAllBytes(sourceDatabase);
        LodPackRegistry.DhPack source = sourcePack(sourceDatabase);

        LodPackRegistry.DhPack first = DhRuntimeSnapshot.prepare(temporary.resolve("game"), source);
        FileTime firstModified = Files.getLastModifiedTime(first.database());
        LodPackRegistry.DhPack second = DhRuntimeSnapshot.prepare(temporary.resolve("game"), source);

        assertEquals(first.database(), second.database());
        assertEquals(firstModified, Files.getLastModifiedTime(second.database()));
        assertEquals(source.directory(), first.directory());
        assertTrue(first.database().startsWith(temporary.resolve("game/dynamicstage/.cache/dh-runtime")));
        assertFalse(Files.isSymbolicLink(first.database()));
        assertFalse(Files.isSameFile(sourceDatabase, first.database()));
        assertArrayEquals(sourceBefore, Files.readAllBytes(sourceDatabase));
        assertPropagationFlags(first.database(), 0, 0);
    }

    @Test
    void rebuildsAfterSourceFingerprintChanges(@TempDir Path temporary) throws Exception {
        Path sourceDatabase = createDatabase(temporary.resolve("source/DistantHorizons.sqlite"), true);
        LodPackRegistry.DhPack source = sourcePack(sourceDatabase);
        LodPackRegistry.DhPack first = DhRuntimeSnapshot.prepare(temporary.resolve("game"), source);

        try (Connection connection = open(sourceDatabase); Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE FullData SET ApplyToParent = 3, ApplyToChildren = 4");
        }
        Files.setLastModifiedTime(sourceDatabase,
                FileTime.fromMillis(Files.getLastModifiedTime(sourceDatabase).toMillis() + 2_000L));

        LodPackRegistry.DhPack second = DhRuntimeSnapshot.prepare(temporary.resolve("game"), source);

        assertNotEquals(first.database(), second.database());
        assertTrue(Files.isRegularFile(first.database()));
        assertTrue(Files.isRegularFile(second.database()));
        assertPropagationFlags(second.database(), 0, 0);
        assertPropagationFlags(sourceDatabase, 3, 4);
    }

    @Test
    void snapshotsSourceWhileDhUpdatesRemainActive(@TempDir Path temporary) throws Exception {
        Path sourceDatabase = createDatabase(temporary.resolve("source/DistantHorizons.sqlite"), true);
        try (Connection connection = open(sourceDatabase); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.executeUpdate("""
                    WITH RECURSIVE rows(value) AS (
                        SELECT 10 UNION ALL SELECT value + 1 FROM rows WHERE value < 4105
                    )
                    INSERT INTO FullData
                    SELECT 0, value, 2, 3, randomblob(2048), X'02', X'03', X'04',
                           X'05', X'06', X'07', X'08', 2, 4, 1, 2
                    FROM rows
                    """);
        }

        AtomicBoolean running = new AtomicBoolean(true);
        AtomicReference<Throwable> writerFailure = new AtomicReference<>();
        CountDownLatch firstUpdate = new CountDownLatch(1);
        Thread writer = new Thread(() -> {
            try (Connection connection = open(sourceDatabase); Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=10000");
                while (running.get()) {
                    statement.executeUpdate(
                            "UPDATE FullData SET DataChecksum = DataChecksum + 1 WHERE PosX = 1");
                    firstUpdate.countDown();
                    Thread.sleep(2L);
                }
            } catch (Throwable error) {
                writerFailure.set(error);
            }
        }, "DH snapshot test writer");
        writer.start();
        assertTrue(firstUpdate.await(10, TimeUnit.SECONDS));

        LodPackRegistry.DhPack snapshot;
        try {
            snapshot = DhRuntimeSnapshot.prepare(temporary.resolve("game"), sourcePack(sourceDatabase));
        } finally {
            running.set(false);
            writer.join(10_000L);
        }

        assertFalse(writer.isAlive());
        assertNull(writerFailure.get());
        assertTrue(Files.isRegularFile(snapshot.database()));
        assertPropagationFlags(snapshot.database(), 0, 0);
        assertPropagationFlags(sourceDatabase, 1, 2);
    }

    @Test
    void removesTemporarySnapshotsAfterSchemaFailure(@TempDir Path temporary) throws Exception {
        Path sourceDatabase = createDatabase(temporary.resolve("source/DistantHorizons.sqlite"), false);
        Path gameDirectory = temporary.resolve("game");

        assertThrows(java.io.IOException.class,
                () -> DhRuntimeSnapshot.prepare(gameDirectory, sourcePack(sourceDatabase)));

        Path root = gameDirectory.resolve("dynamicstage/.cache/dh-runtime");
        try (var entries = Files.list(root)) {
            assertEquals(0L, entries.count());
        }
    }

    private static Path createDatabase(Path database, boolean validSchema) throws Exception {
        Files.createDirectories(database.getParent());
        try (Connection connection = open(database); Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=DELETE");
            if (!validSchema) {
                statement.execute("CREATE TABLE FullData (DetailLevel INTEGER)");
                return database;
            }
            statement.execute("""
                    CREATE TABLE FullData (
                        DetailLevel INTEGER, PosX INTEGER, PosZ INTEGER,
                        DataChecksum INTEGER, Data BLOB,
                        ColumnGenerationStep BLOB, ColumnWorldCompressionMode BLOB, Mapping BLOB,
                        NorthAdjData BLOB, SouthAdjData BLOB, EastAdjData BLOB, WestAdjData BLOB,
                        DataFormatVersion INTEGER, CompressionMode INTEGER,
                        ApplyToParent INTEGER, ApplyToChildren INTEGER
                    )
                    """);
            statement.executeUpdate("""
                    INSERT INTO FullData VALUES (
                        0, 1, 2, 3, X'01', X'02', X'03', X'04',
                        X'05', X'06', X'07', X'08', 2, 4, 1, 2
                    )
                    """);
        }
        return database;
    }

    private static LodPackRegistry.DhPack sourcePack(Path database) {
        Path directory = database.getParent().resolve("pack");
        return new LodPackRegistry.DhPack(new ResourceLocation("test", "source"),
                directory, database.getParent(), database);
    }

    private static Connection open(Path database) throws Exception {
        return DriverManager.getConnection(SQLITE_PREFIX + database.toAbsolutePath().normalize());
    }

    private static void assertPropagationFlags(Path database, int parent, int children) throws Exception {
        try (Connection connection = open(database);
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT ApplyToParent, ApplyToChildren FROM FullData")) {
            assertTrue(result.next());
            assertEquals(parent, result.getInt(1));
            assertEquals(children, result.getInt(2));
        }
    }
}
