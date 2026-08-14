package vibe.liteming.dynamicstage.client.lod;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Discovers native LOD caches and creates either linked or isolated packages. */
public final class LodPackImporter {
    private static final String DH_DATABASE = "DistantHorizons.sqlite";
    private static final Pattern VOXY_WORLD_ID = Pattern.compile("[0-9a-f]{32}");
    private static final int MAX_SCAN_DEPTH = 8;

    private LodPackImporter() {
    }

    public static ImportResult importPack(ResourceLocation id, Path source) throws IOException {
        return importPack(LodPackRegistry.rootDirectory(), id, source, Mode.COPY);
    }

    public static ImportResult importPack(Path packageRoot, ResourceLocation id, Path source, Mode mode)
            throws IOException {
        Path root = packageRoot.toAbsolutePath().normalize();
        Path normalizedSource = source.toAbsolutePath().normalize();
        if (!Files.exists(normalizedSource, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("LOD source does not exist: " + normalizedSource);
        }

        List<Candidate> candidates = discover(normalizedSource);
        if (candidates.isEmpty()) {
            throw new IOException("No DH SQLite or Voxy RocksDB cache found under " + normalizedSource);
        }
        if (candidates.size() != 1) {
            throw new IOException(ambiguousMessage(normalizedSource, candidates));
        }

        Path destination = root.resolve(id.getNamespace()).resolve(id.getPath()).normalize();
        if (!destination.startsWith(root)) {
            throw new IOException("LOD package destination is unsafe");
        }
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("LOD package already exists: " + id);
        }

        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) {
            throw new IOException("LOD package root cannot be a symbolic link");
        }

        Candidate candidate = candidates.get(0);
        Path stagingRoot = root.resolve(".import-" + UUID.randomUUID()).normalize();
        Path stagingPackage = stagingRoot.resolve(id.getNamespace()).resolve(id.getPath()).normalize();
        CopyStats stats = new CopyStats();
        try {
            Files.createDirectories(stagingPackage);
            if (mode == Mode.COPY) {
                if (candidate.backend == Backend.DISTANT_HORIZONS) {
                    importDh(candidate.path, stagingPackage, stats);
                } else {
                    importVoxy(candidate, stagingPackage, stats);
                }
            }
            writeManifest(stagingPackage, candidate, mode);
            LodPackRegistry.load(stagingRoot, id);

            createSafeDirectories(root, destination.getParent());
            try {
                Files.move(stagingPackage, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(stagingPackage, destination);
            }
            return new ImportResult(id, candidate.backend, mode, candidate.path, destination,
                    stats.files, stats.bytes);
        } finally {
            deleteTree(stagingRoot);
        }
    }

    static List<Candidate> discover(Path source) throws IOException {
        Path normalized = source.toAbsolutePath().normalize();
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        if (Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
            addFileCandidate(normalized, candidates);
        } else if (Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            addVoxyCandidate(normalized, candidates);
            Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                        throws IOException {
                    int depth = normalized.equals(directory) ? 0 : normalized.relativize(directory).getNameCount();
                    if (depth > MAX_SCAN_DEPTH) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (!normalized.equals(directory) && addVoxyCandidate(directory, candidates)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isRegularFile()) {
                        addFileCandidate(file, candidates);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException error) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
            });
        }
        return candidates.values().stream()
                .sorted(Comparator.comparing((Candidate value) -> value.backend.name())
                        .thenComparing(value -> value.path.toString()))
                .toList();
    }

    private static void addFileCandidate(Path file, Map<String, Candidate> candidates) throws IOException {
        if (DH_DATABASE.equals(file.getFileName().toString()) && hasSqliteHeader(file)) {
            addCandidate(new Candidate(Backend.DISTANT_HORIZONS, file.toAbsolutePath().normalize(), null),
                    candidates);
        }
    }

    private static boolean addVoxyCandidate(Path directory, Map<String, Candidate> candidates)
            throws IOException {
        if (!"storage".equals(directory.getFileName() == null ? "" : directory.getFileName().toString())
                || directory.getParent() == null || directory.getParent().getFileName() == null) {
            return false;
        }
        String worldId = directory.getParent().getFileName().toString();
        if (!VOXY_WORLD_ID.matcher(worldId).matches()
                || !Files.isRegularFile(directory.resolve("CURRENT"), LinkOption.NOFOLLOW_LINKS)
                || !hasRocksManifest(directory)) {
            return false;
        }
        addCandidate(new Candidate(Backend.VOXY, directory.toAbsolutePath().normalize(), worldId), candidates);
        return true;
    }

    private static void addCandidate(Candidate candidate, Map<String, Candidate> candidates) {
        candidates.putIfAbsent(candidate.backend + "\0" + candidate.path, candidate);
    }

    private static void importDh(Path database, Path stagingPackage, CopyStats stats) throws IOException {
        Path destination = Files.createDirectories(stagingPackage.resolve("dh"));
        copyFile(database, destination.resolve(DH_DATABASE), stats);
        for (String suffix : List.of("-wal", "-shm")) {
            Path sidecar = database.resolveSibling(DH_DATABASE + suffix);
            if (Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS)) {
                copyFile(sidecar, destination.resolve(sidecar.getFileName()), stats);
            }
        }
    }

    private static void importVoxy(Candidate candidate, Path stagingPackage, CopyStats stats) throws IOException {
        Path destination = stagingPackage.resolve("voxy").resolve(candidate.worldId).resolve("storage");
        copyTree(candidate.path, destination, stats);
    }

    private static void copyTree(Path source, Path destination, CopyStats stats) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
                    throws IOException {
                if (Files.isSymbolicLink(directory)) {
                    throw new IOException("LOD source contains a symbolic link: " + directory);
                }
                Files.createDirectories(destination.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                if (!attributes.isRegularFile() || attributes.isSymbolicLink()) {
                    throw new IOException("LOD source contains an unsupported file: " + file);
                }
                copyFile(file, destination.resolve(source.relativize(file)), stats);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void copyFile(Path source, Path destination, CopyStats stats) throws IOException {
        Files.createDirectories(destination.getParent());
        Files.copy(source, destination);
        stats.files++;
        stats.bytes += Files.size(destination);
    }

    private static void writeManifest(Path directory, Candidate candidate, Mode mode) throws IOException {
        JsonObject manifest = new JsonObject();
        manifest.addProperty("formatVersion", LodPackRegistry.FORMAT_VERSION);
        manifest.addProperty("backend", candidate.backend.manifestName);
        manifest.addProperty("minecraftVersion", "1.20.1");
        if (candidate.backend == Backend.DISTANT_HORIZONS) {
            manifest.addProperty("distantHorizonsVersion", LodPackRegistry.DH_VERSION);
        } else {
            manifest.addProperty("voxyVersion", LodPackRegistry.VOXY_VERSION);
            manifest.addProperty("worldId", candidate.worldId);
        }
        if (mode == Mode.LINK) {
            manifest.addProperty("sourcePath", candidate.path.toString());
        }
        manifest.addProperty("minY", LodPackRegistry.STAGE_MIN_Y);
        manifest.addProperty("height", LodPackRegistry.STAGE_HEIGHT);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(manifest) + System.lineSeparator();
        Files.writeString(directory.resolve("manifest.json"), json, StandardCharsets.UTF_8);
    }

    private static boolean hasSqliteHeader(Path file) throws IOException {
        byte[] expected = "SQLite format 3\0".getBytes(StandardCharsets.US_ASCII);
        byte[] actual = new byte[expected.length];
        try (InputStream input = Files.newInputStream(file)) {
            return input.readNBytes(actual, 0, actual.length) == actual.length
                    && java.util.Arrays.equals(expected, actual);
        }
    }

    private static boolean hasRocksManifest(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            return files.anyMatch(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    && path.getFileName().toString().startsWith("MANIFEST-"));
        }
    }

    private static String ambiguousMessage(Path source, List<Candidate> candidates) {
        List<String> displayed = new ArrayList<>();
        candidates.stream().limit(6).forEach(candidate -> displayed.add(
                candidate.backend.manifestName + "=" + candidate.path));
        String suffix = candidates.size() > displayed.size() ? ", ..." : "";
        return "Multiple LOD caches found under " + source
                + "; point the source path at one cache: " + String.join(", ", displayed) + suffix;
    }

    private static void createSafeDirectories(Path root, Path directory) throws IOException {
        Path current = root;
        for (Path part : root.relativize(directory)) {
            current = current.resolve(part);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("LOD package path is unsafe: " + current);
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
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

    public enum Backend {
        DISTANT_HORIZONS("distanthorizons"),
        VOXY("voxy");

        private final String manifestName;

        Backend(String manifestName) {
            this.manifestName = manifestName;
        }

        public String displayName() {
            return manifestName;
        }
    }

    public enum Mode {
        LINK,
        COPY
    }

    public record ImportResult(ResourceLocation id, Backend backend, Mode mode, Path source, Path destination,
                               long fileCount, long byteCount) {
    }

    record Candidate(Backend backend, Path path, String worldId) {
    }

    private static final class CopyStats {
        private long files;
        private long bytes;
    }
}
