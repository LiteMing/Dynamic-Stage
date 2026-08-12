package vibe.liteming.dynamicstage.backdrop;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Content-addressed storage for server-generated backdrop products. */
public final class BackdropProducts {

    public static final long MAX_PRODUCT_BYTES = BackdropBlobIO.MAX_BLOB_BYTES;

    private BackdropProducts() {
    }

    public static Product store(Path worldRoot, String stageId, byte[] bytes, String requestKey) throws IOException {
        validateRequestKey(requestKey);
        if (bytes.length <= 0 || bytes.length > MAX_PRODUCT_BYTES) {
            throw new IOException("Backdrop product size is outside the supported range: " + bytes.length);
        }
        String hash = sha256Hex(bytes);
        Path stageDir = stageDirectory(worldRoot, stageId);
        Files.createDirectories(stageDir);
        Path target = stageDir.resolve(hash + ".sdb");
        if (!Files.isRegularFile(target) || Files.size(target) != bytes.length
                || !hash.equals(sha256Hex(target))) {
            Path temporary = Files.createTempFile(stageDir, hash, ".tmp");
            try {
                Files.write(temporary, bytes);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        Files.writeString(stageDir.resolve(requestKey + ".ref"), hash, StandardCharsets.US_ASCII);
        return new Product(target, hash, bytes.length);
    }

    @Nullable
    public static Product findByRequest(Path worldRoot, String stageId, String requestKey) {
        if (!isRequestKey(requestKey)) {
            return null;
        }
        Path stageDir = stageDirectory(worldRoot, stageId);
        Path ref = stageDir.resolve(requestKey + ".ref");
        try {
            if (!Files.isRegularFile(ref)) {
                return null;
            }
            String hash = Files.readString(ref, StandardCharsets.US_ASCII).trim();
            Path product = resolve(worldRoot, stageId, hash);
            if (product == null) {
                return null;
            }
            long bytes = Files.size(product);
            if (bytes <= 0 || bytes > MAX_PRODUCT_BYTES || !hash.equals(sha256Hex(product))) {
                return null;
            }
            return new Product(product, hash, bytes);
        } catch (IOException e) {
            return null;
        }
    }

    @Nullable
    public static Path resolve(MinecraftServer server, String stageId, String hash) {
        return resolve(server.getWorldPath(LevelResource.ROOT), stageId, hash);
    }

    @Nullable
    public static Path resolve(Path worldRoot, String stageId, String hash) {
        if (!isSha256(hash)) {
            return null;
        }
        Path stageDir = stageDirectory(worldRoot, stageId);
        Path product = stageDir.resolve(hash + ".sdb").normalize();
        if (!product.getParent().equals(stageDir) || !Files.isRegularFile(product)) {
            return null;
        }
        return product;
    }

    public static Path stageDirectory(Path worldRoot, String stageId) {
        return worldRoot.resolve("data").resolve("dynamicstage").resolve("backdrops")
                .resolve(sha256Hex(stageId.getBytes(StandardCharsets.UTF_8)).substring(0, 24))
                .toAbsolutePath().normalize();
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (java.io.InputStream input = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static boolean isRequestKey(String value) {
        return value != null && value.matches("[0-9a-f]{24}");
    }

    private static void validateRequestKey(String value) throws IOException {
        if (!isRequestKey(value)) {
            throw new IOException("Invalid backdrop request key");
        }
    }

    public record Product(Path path, String hash, long bytes) {
    }
}
