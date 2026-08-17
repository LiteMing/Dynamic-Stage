package vibe.liteming.dynamicstage.lod;

import com.google.gson.JsonParser;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes portable LOD package archives for client exports and server distribution. */
public final class LodArchiveWriter {
    public static final int MAX_ENTRIES = 16_384;

    private LodArchiveWriter() {
    }

    public static ArchiveInfo create(Path packageDirectory, Path output, boolean replaceExisting) throws IOException {
        Path source = packageDirectory.toAbsolutePath().normalize();
        Path target = output.toAbsolutePath().normalize();
        Path manifest = source.resolve("manifest.json");
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("LOD package directory is incomplete: " + source);
        }
        try (var reader = Files.newBufferedReader(manifest)) {
            if (JsonParser.parseReader(reader).getAsJsonObject().has("sourcePath")) {
                throw new IOException("Linked LOD packages must be copied before they can be distributed");
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid LOD package manifest: " + manifest, e);
        }
        if (target.startsWith(source)) {
            throw new IOException("LOD archive cannot be written inside its source package");
        }
        if (!replaceExisting && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("LOD archive already exists: " + target);
        }
        Path parent = target.getParent();
        if (parent == null) {
            throw new IOException("LOD archive has no parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        int fileCount = 0;
        try {
            try (ZipOutputStream zip = new ZipOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                zip.setLevel(9);
                try (var files = Files.walk(source)) {
                    for (Path file : files.filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(path -> source.relativize(path).toString())).toList()) {
                        if (Files.isSymbolicLink(file)) {
                            throw new IOException("LOD package contains a symbolic link: " + file);
                        }
                        Path relative = source.relativize(file);
                        if (skipRuntimeFile(relative)) {
                            continue;
                        }
                        if (++fileCount > MAX_ENTRIES) {
                            throw new IOException("LOD package contains too many files");
                        }
                        ZipEntry entry = new ZipEntry(relative.toString().replace('\\', '/'));
                        entry.setTime(0L);
                        zip.putNextEntry(entry);
                        Files.copy(file, zip);
                        zip.closeEntry();
                    }
                }
            }
            try {
                if (replaceExisting) {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                }
            } catch (AtomicMoveNotSupportedException e) {
                if (replaceExisting) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(temporary, target);
                }
            }
            return new ArchiveInfo(target, Files.size(target),
                    vibe.liteming.dynamicstage.util.ContentHash.sha256Hex(target), fileCount);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean skipRuntimeFile(Path relative) {
        String name = relative.getFileName().toString();
        return "LOCK".equals(name) || "LOG".equals(name) || name.startsWith("LOG.old.");
    }

    public record ArchiveInfo(Path path, long bytes, String sha256, int files) {
    }
}
