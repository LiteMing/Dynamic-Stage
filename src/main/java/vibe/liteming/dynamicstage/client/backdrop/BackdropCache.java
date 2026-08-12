package vibe.liteming.dynamicstage.client.backdrop;

import net.minecraft.client.Minecraft;
import vibe.liteming.dynamicstage.backdrop.BackdropProducts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Client cache paths derived only from hashes, never raw stage identifiers. */
public final class BackdropCache {

    private BackdropCache() {
    }

    public static Path directory() throws IOException {
        Path directory = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("dynamicstage_cache").toAbsolutePath().normalize();
        Files.createDirectories(directory);
        return directory;
    }

    public static Path complete(String stageId, String hash) throws IOException {
        return directory().resolve(fileStem(stageId, hash) + ".sdb");
    }

    public static Path partial(String stageId, String hash) throws IOException {
        return directory().resolve(fileStem(stageId, hash) + ".sdb.part");
    }

    private static String fileStem(String stageId, String hash) {
        if (!BackdropProducts.isSha256(hash)) {
            throw new IllegalArgumentException("Invalid backdrop hash");
        }
        String stageHash = BackdropProducts.sha256Hex(stageId.getBytes(StandardCharsets.UTF_8));
        return stageHash.substring(0, 24) + '_' + hash;
    }
}
