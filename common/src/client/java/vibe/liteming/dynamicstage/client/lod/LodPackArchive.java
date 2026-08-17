package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.lod.LodArchiveWriter;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Creates and installs immutable .dstlod ZIP archives. */
public final class LodPackArchive {
    public static final int MAX_ENTRIES = LodArchiveWriter.MAX_ENTRIES;

    private LodPackArchive() {
    }

    public static ArchiveInfo create(Path packageDirectory, Path output) throws IOException {
        LodArchiveWriter.ArchiveInfo info = LodArchiveWriter.create(packageDirectory, output, false);
        return new ArchiveInfo(info.path(), info.bytes(), info.sha256(), info.files());
    }

    public static void install(Path archive, Path packageRoot, ResourceLocation id,
                               long maxExtractedBytes) throws IOException {
        Path root = packageRoot.toAbsolutePath().normalize();
        Path target = root.resolve(id.getNamespace()).resolve(id.getPath()).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("LOD package destination is unsafe");
        }
        if (Files.isSymbolicLink(root)) {
            throw new IOException("LOD package root cannot be a symbolic link");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("LOD package destination already exists: " + target);
        }
        Files.createDirectories(root);
        Path stagingRoot = root.resolve(".download-" + UUID.randomUUID()).normalize();
        Path stagingPackage = stagingRoot.resolve(id.getNamespace()).resolve(id.getPath()).normalize();
        long extracted = 0L;
        int entries = 0;
        Set<String> names = new HashSet<>();
        try {
            Files.createDirectories(stagingPackage);
            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                    if (++entries > MAX_ENTRIES) {
                        throw new IOException("LOD archive contains too many entries");
                    }
                    String name = entry.getName().replace('\\', '/');
                    if (name.isBlank() || name.startsWith("/") || name.contains(":")) {
                        throw new IOException("LOD archive contains an unsafe path: " + name);
                    }
                    if (!names.add(name)) {
                        throw new IOException("LOD archive contains a duplicate path: " + name);
                    }
                    Path destination = stagingPackage.resolve(name).normalize();
                    if (!destination.startsWith(stagingPackage)) {
                        throw new IOException("LOD archive escapes its package directory");
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        try (var output = new BufferedOutputStream(Files.newOutputStream(destination))) {
                            byte[] buffer = new byte[64 * 1024];
                            int read;
                            while ((read = zip.read(buffer)) >= 0) {
                                if (read == 0) {
                                    continue;
                                }
                                extracted += read;
                                if (extracted > maxExtractedBytes) {
                                    throw new IOException("LOD archive exceeds its extraction limit");
                                }
                                output.write(buffer, 0, read);
                            }
                        }
                    }
                    zip.closeEntry();
                }
            }
            LodPackRegistry.load(stagingRoot, id);
            Files.createDirectories(target.getParent());
            try {
                Files.move(stagingPackage, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(stagingPackage, target);
            }
        } finally {
            deleteTree(stagingRoot);
        }
    }

    static void deleteTree(Path root) {
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

    public record ArchiveInfo(Path path, long bytes, String sha256, int files) {
    }
}
