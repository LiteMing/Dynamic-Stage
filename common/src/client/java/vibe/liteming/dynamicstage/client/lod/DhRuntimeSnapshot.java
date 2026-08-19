package vibe.liteming.dynamicstage.client.lod;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** Materializes immutable DH packages into writable, content-addressed runtime databases. */
final class DhRuntimeSnapshot {
    private static final Logger LOGGER = LoggerFactory.getLogger(DhRuntimeSnapshot.class);
    private static final String DATABASE = "DistantHorizons.sqlite";
    private static final String MARKER = "runtime.json";
    private static final long MAX_MARKER_BYTES = 16 * 1024L;

    private DhRuntimeSnapshot() {
    }

    static LodPackRegistry.DhPack prepare(Path gameDirectory, LodPackRegistry.DhPack source)
            throws IOException {
        Path root = gameDirectory.toAbsolutePath().normalize()
                .resolve("dynamicstage").resolve(".cache").resolve("dh-runtime")
                .toAbsolutePath().normalize();
        Files.createDirectories(root);

        Fingerprint before = Fingerprint.capture(source.database());
        Path reusableDirectory = root.resolve(before.key()).normalize();
        Path reusableDatabase = reusableDirectory.resolve(DATABASE).normalize();
        requireSafePaths(root, reusableDirectory, reusableDatabase);
        if (isReusable(reusableDirectory, reusableDatabase, before)) {
            return runtimePack(source, reusableDirectory, reusableDatabase);
        }

        Path temporary = root.resolve(".snapshot-" + UUID.randomUUID()).normalize();
        Files.createDirectories(temporary);
        try {
            // VACUUM INTO reads a consistent SQLite transaction snapshot. Source updates
            // may continue without invalidating the resulting private runtime database.
            DhPackOptimizer.snapshotReadOnly(source.database(), temporary.resolve(DATABASE));
            Fingerprint after = Fingerprint.capture(source.database());
            Path directory = root.resolve(after.key()).normalize();
            Path database = directory.resolve(DATABASE).normalize();
            requireSafePaths(root, directory, database);
            writeMarker(temporary.resolve(MARKER), after, temporary.resolve(DATABASE));
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (isReusable(directory, database, after)) {
                    deleteTree(temporary, root);
                    return runtimePack(source, directory, database);
                }
                deleteTree(directory, root);
            }
            movePublished(temporary, directory);
            if (!before.equals(after)) {
                LOGGER.info("Created DH runtime snapshot while source updates remained active: {}", database);
            } else {
                LOGGER.info("Created DH runtime snapshot {} from {}", database, source.database());
            }
            return runtimePack(source, directory, database);
        } catch (IOException error) {
            deleteTree(temporary, root);
            throw error;
        }
    }

    private static void requireSafePaths(Path root, Path directory, Path database) throws IOException {
        if (!directory.startsWith(root) || !database.startsWith(directory)) {
            throw new IOException("DH runtime snapshot path is unsafe");
        }
    }

    private static LodPackRegistry.DhPack runtimePack(LodPackRegistry.DhPack source,
                                                       Path directory, Path database) {
        return new LodPackRegistry.DhPack(source.id(), source.directory(), directory, database);
    }

    private static boolean isReusable(Path directory, Path database, Fingerprint fingerprint) {
        try {
            if (!Files.isRegularFile(database, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isRegularFile(directory.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(database.resolveSibling(DATABASE + "-wal"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(database.resolveSibling(DATABASE + "-shm"), LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(database.resolveSibling(DATABASE + "-journal"), LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            if (Files.size(directory.resolve(MARKER)) > MAX_MARKER_BYTES) {
                return false;
            }
            JsonObject marker = com.google.gson.JsonParser.parseString(
                    Files.readString(directory.resolve(MARKER), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!fingerprint.key().equals(marker.get("sourceFingerprint").getAsString())) {
                return false;
            }
            long size = marker.get("databaseBytes").getAsLong();
            long modified = marker.get("databaseModifiedMillis").getAsLong();
            BasicFileAttributes attributes = Files.readAttributes(database,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return attributes.size() == size && attributes.lastModifiedTime().toMillis() == modified
                    && hasSqliteHeader(database);
        } catch (RuntimeException | IOException ignored) {
            return false;
        }
    }

    private static void writeMarker(Path marker, Fingerprint fingerprint, Path database) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(database,
                BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        JsonObject value = new JsonObject();
        value.addProperty("formatVersion", 1);
        value.addProperty("sourceFingerprint", fingerprint.key());
        value.addProperty("sourcePath", fingerprint.sourcePath());
        value.addProperty("databaseBytes", attributes.size());
        value.addProperty("databaseModifiedMillis", attributes.lastModifiedTime().toMillis());
        Files.writeString(marker,
                new GsonBuilder().setPrettyPrinting().create().toJson(value) + System.lineSeparator(),
                StandardCharsets.UTF_8);
    }

    private static boolean hasSqliteHeader(Path database) throws IOException {
        byte[] expected = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
        byte[] actual = new byte[expected.length];
        try (var input = Files.newInputStream(database)) {
            return input.readNBytes(actual, 0, actual.length) == actual.length
                    && java.util.Arrays.equals(expected, actual);
        }
    }

    private static void movePublished(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException error) {
            Files.move(temporary, target);
        }
    }

    private static void deleteTree(Path target, Path root) {
        if (target == null || !target.normalize().startsWith(root.normalize())
                || !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(target, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }
                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }

    private record Fingerprint(String key, String sourcePath, String state) {
        private static Fingerprint capture(Path source) throws IOException {
            Path normalized = source.toAbsolutePath().normalize();
            Path real = normalized.toRealPath(LinkOption.NOFOLLOW_LINKS);
            StringBuilder state = new StringBuilder(real.toString());
            append(state, real);
            append(state, real.resolveSibling(real.getFileName() + "-wal"));
            append(state, real.resolveSibling(real.getFileName() + "-shm"));
            String value = state.toString();
            return new Fingerprint(sha256(value), real.toString(), value);
        }

        private static void append(StringBuilder value, Path path) throws IOException {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                value.append("|missing");
                return;
            }
            BasicFileAttributes attributes = Files.readAttributes(path,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new IOException("DH SQLite state path is not a regular file: " + path);
            }
            value.append('|').append(path.getFileName()).append('|').append(attributes.size())
                    .append('|').append(attributes.lastModifiedTime().toMillis())
                    .append('|').append(attributes.fileKey());
        }

        private static String sha256(String value) {
            try {
                return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException error) {
                throw new IllegalStateException("SHA-256 is unavailable", error);
            }
        }
    }
}
