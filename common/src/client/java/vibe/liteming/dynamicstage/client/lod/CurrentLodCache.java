package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Identifies the native LOD storage currently opened for the client's source level. */
public record CurrentLodCache(LodPackImporter.Backend backend, Path source, String worldIdentifier) {

    public CurrentLodCache {
        source = source.toAbsolutePath().normalize();
        if (worldIdentifier == null || worldIdentifier.isBlank()) {
            throw new IllegalArgumentException("LOD world identifier is empty");
        }
    }

    @Nullable
    public static CurrentLodCache discover() throws IOException {
        List<CurrentLodCache> candidates = new ArrayList<>();
        DhBackdropRuntime.CurrentSource dh = DhBackdropRuntime.currentSource();
        if (dh != null && Files.isRegularFile(dh.database())) {
            candidates.add(new CurrentLodCache(
                    LodPackImporter.Backend.DISTANT_HORIZONS, dh.database(), dh.worldIdentifier()));
        }
        for (VoxyBackdropRuntime.CurrentSource voxy : VoxyBackdropRuntime.currentSources()) {
            if (Files.isDirectory(voxy.storage())) {
                candidates.add(new CurrentLodCache(
                        LodPackImporter.Backend.VOXY, voxy.storage(), voxy.worldId()));
            }
        }
        return select(candidates);
    }

    @Nullable
    static CurrentLodCache select(List<CurrentLodCache> candidates) throws IOException {
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() != 1) {
            throw new IOException("Multiple native LOD caches are active; use the full /dstage start command");
        }
        return candidates.get(0);
    }

    public ResourceLocation automaticPackId(Path gameDirectory) {
        String backendPath = backend == LodPackImporter.Backend.DISTANT_HORIZONS ? "dh" : "voxy";
        Path root = gameDirectory.toAbsolutePath().normalize();
        String portablePath = source.startsWith(root)
                ? root.relativize(source).toString().replace('\\', '/') : source.toString();
        return new ResourceLocation("dynamicstage", "auto/" + backendPath + '/' + shortHash(
                backend.name() + '\0' + worldIdentifier + '\0' + portablePath));
    }

    public boolean matches(LodPackRegistry.Pack pack) {
        Path selected = pack instanceof LodPackRegistry.DhPack dh
                ? dh.database() : ((LodPackRegistry.VoxyPack) pack).storageDirectory();
        try {
            return Files.isSameFile(source, selected);
        } catch (IOException e) {
            return false;
        }
    }

    public static ResourceLocation missingPackId(String stageId, ResourceLocation dimension) {
        return new ResourceLocation("dynamicstage", "auto/missing/" + shortHash(stageId + '\0' + dimension));
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 10);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
