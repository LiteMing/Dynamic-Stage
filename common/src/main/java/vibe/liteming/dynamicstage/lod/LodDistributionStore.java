package vibe.liteming.dynamicstage.lod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import vibe.liteming.dynamicstage.platform.StagePlatform;
import vibe.liteming.dynamicstage.util.ContentHash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side catalog of downloadable LOD archives. */
public final class LodDistributionStore {
    private static final int FORMAT_VERSION = 1;
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<Path, LocalArchiveState> LOCAL_ARCHIVES = new ConcurrentHashMap<>();
    private static final Map<Path, HostedArchive> GENERATED_ARCHIVES = new ConcurrentHashMap<>();

    private LodDistributionStore() {
    }

    public static LodPackageOffer find(MinecraftServer server, ResourceLocation id) {
        if (server == null || id == null) {
            return null;
        }
        try {
            LodPackageOffer configured = read(server).get(id.toString());
            if (configured != null && !configured.serverHosted()) {
                return configured;
            }
            HostedArchive hosted = hostedArchive(server, id);
            if (hosted == null) {
                return configured;
            }
            if (configured != null && (configured.bytes() != hosted.offer().bytes()
                    || !configured.sha256().equals(hosted.offer().sha256()))) {
                return null;
            }
            return configured == null ? hosted.offer() : configured;
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }

    /** Resolves a direct archive or lazily archives an unpacked server package directory. */
    public static HostedArchive hostedArchive(MinecraftServer server, ResourceLocation id) throws IOException {
        if (server == null || id == null) {
            return null;
        }
        return hostedArchive(resourceRoots(server), id);
    }

    static HostedArchive hostedArchive(List<ResourceRoot> resources, ResourceLocation id) throws IOException {
        for (ResourceRoot resource : resources) {
            Path archive = archivePath(resource.packages(), id);
            if (Files.isRegularFile(archive, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return describeArchive(archive, false);
            }
            Path directory = packageDirectory(resource.packages(), id);
            if (!Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            HostedArchive cached = GENERATED_ARCHIVES.get(directory);
            if (cached != null && Files.isRegularFile(cached.path(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return cached;
            }
            Path output = resource.generatedArchives().resolve(id.getNamespace())
                    .resolve(id.getPath() + ".dstlod").normalize();
            LodArchiveWriter.ArchiveInfo info = LodArchiveWriter.create(directory, output, true);
            HostedArchive generated = new HostedArchive(info.path(),
                    new LodPackageOffer(LodPackageOffer.Delivery.OPTIONAL, "", info.bytes(), info.sha256(), true),
                    true);
            GENERATED_ARCHIVES.put(directory, generated);
            LOCAL_ARCHIVES.put(info.path(), new LocalArchiveState(info.bytes(),
                    Files.getLastModifiedTime(info.path()).toMillis(), info.sha256()));
            return generated;
        }
        return null;
    }

    /** Clears cached archive metadata and validates all discoverable server packages. */
    public static ReloadResult reload(MinecraftServer server) throws IOException {
        LOCAL_ARCHIVES.clear();
        GENERATED_ARCHIVES.clear();
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        List<String> errors = new ArrayList<>();
        for (ResourceRoot resource : resourceRoots(server)) {
            discover(resource.packages(), ids, errors);
        }
        int hosted = 0;
        int generated = 0;
        for (ResourceLocation id : ids) {
            try {
                HostedArchive archive = hostedArchive(server, id);
                if (archive != null) {
                    hosted++;
                    if (archive.generated()) {
                        generated++;
                    }
                }
            } catch (IOException | RuntimeException e) {
                errors.add(id + ": " + rootMessage(e));
            }
        }
        return new ReloadResult(hosted, generated, List.copyOf(errors));
    }

    public static Map<String, LodPackageOffer> read(MinecraftServer server) throws IOException {
        Path path = path(server);
        if (!Files.isRegularFile(path)) {
            return Map.of();
        }
        if (Files.size(path) > MAX_FILE_BYTES) {
            throw new IOException("LOD distribution catalog is too large");
        }
        JsonObject root;
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid LOD distribution catalog", e);
        }
        if (!root.has("formatVersion") || root.get("formatVersion").getAsInt() != FORMAT_VERSION) {
            throw new IOException("Unsupported LOD distribution catalog format");
        }
        Map<String, LodPackageOffer> offers = new LinkedHashMap<>();
        if (!root.has("packs") || !root.get("packs").isJsonObject()) {
            return offers;
        }
        for (var entry : root.getAsJsonObject("packs").entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(entry.getKey());
            if (id == null || !entry.getValue().isJsonObject()) {
                throw new IOException("Invalid LOD package id in distribution catalog: " + entry.getKey());
            }
            JsonObject value = entry.getValue().getAsJsonObject();
            String delivery = value.has("delivery") ? value.get("delivery").getAsString() : "optional";
            LodPackageOffer.Delivery mode;
            try {
                mode = LodPackageOffer.Delivery.valueOf(delivery.toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid delivery for " + id + ": " + delivery, e);
            }
            if (mode == LodPackageOffer.Delivery.LOCAL) {
                continue;
            }
            boolean serverHosted = value.has("serverHosted") && value.get("serverHosted").getAsBoolean();
            offers.put(id.toString(), new LodPackageOffer(mode,
                    serverHosted ? "" : value.get("url").getAsString(), value.get("bytes").getAsLong(),
                    value.get("sha256").getAsString(), serverHosted));
        }
        return Map.copyOf(offers);
    }

    public static void put(MinecraftServer server, ResourceLocation id, LodPackageOffer offer) throws IOException {
        Map<String, LodPackageOffer> offers = new LinkedHashMap<>(read(server));
        offers.put(id.toString(), offer);
        write(server, offers);
    }

    public static boolean remove(MinecraftServer server, ResourceLocation id) throws IOException {
        Map<String, LodPackageOffer> offers = new LinkedHashMap<>(read(server));
        if (offers.remove(id.toString()) == null) {
            return false;
        }
        write(server, offers);
        return true;
    }

    public static Path path(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("dynamicstage")
                .resolve("lod-distribution.json").toAbsolutePath().normalize();
    }

    /** Convention-based archive location that requires no server command or HTTP service. */
    public static Path localArchive(MinecraftServer server, ResourceLocation id) {
        return localArchiveRoot(server).resolve(id.getNamespace()).resolve(id.getPath() + ".dstlod").normalize();
    }

    public static Path localArchiveRoot(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("dynamicstage").resolve("lodpacks")
                .toAbsolutePath().normalize();
    }

    /** Portable server-level resource root that can be copied as one dynamicstage directory. */
    public static Path serverResourceRoot() {
        return StagePlatform.gameDirectory().resolve("dynamicstage").resolve("lodpacks")
                .toAbsolutePath().normalize();
    }

    private static List<ResourceRoot> resourceRoots(MinecraftServer server) {
        Path serverRoot = serverResourceRoot();
        Path worldRoot = localArchiveRoot(server);
        Path serverGenerated = StagePlatform.gameDirectory().resolve("dynamicstage").resolve(".cache")
                .resolve("lodarchives").toAbsolutePath().normalize();
        ResourceRoot portable = new ResourceRoot(serverRoot, serverGenerated);
        if (serverRoot.equals(worldRoot)) {
            return List.of(portable);
        }
        Path worldGenerated = worldRoot.getParent().resolve(".cache").resolve("lodarchives").normalize();
        return List.of(portable, new ResourceRoot(worldRoot, worldGenerated));
    }

    private static Path archivePath(Path root, ResourceLocation id) {
        return root.resolve(id.getNamespace()).resolve(id.getPath() + ".dstlod").normalize();
    }

    private static Path packageDirectory(Path root, ResourceLocation id) {
        return root.resolve(id.getNamespace()).resolve(id.getPath()).normalize();
    }

    private static HostedArchive describeArchive(Path archive, boolean generated) throws IOException {
        Path normalized = archive.toAbsolutePath().normalize();
        long bytes = Files.size(normalized);
        if (bytes <= 0L || bytes > LodPackageOffer.MAX_BYTES) {
            throw new IOException("LOD archive has an invalid size: " + normalized);
        }
        long modified = Files.getLastModifiedTime(normalized,
                java.nio.file.LinkOption.NOFOLLOW_LINKS).toMillis();
        LocalArchiveState cached = LOCAL_ARCHIVES.get(normalized);
        if (cached == null || cached.bytes() != bytes || cached.modified() != modified) {
            cached = new LocalArchiveState(bytes, modified, ContentHash.sha256Hex(normalized));
            LOCAL_ARCHIVES.put(normalized, cached);
        }
        return new HostedArchive(normalized,
                new LodPackageOffer(LodPackageOffer.Delivery.OPTIONAL, "", bytes, cached.sha256(), true),
                generated);
    }

    private static void discover(Path root, Set<ResourceLocation> ids, List<String> errors) {
        if (!Files.isDirectory(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                String name = file.getFileName().toString();
                Path relative;
                if ("manifest.json".equals(name)) {
                    relative = root.relativize(file.getParent());
                } else if (name.endsWith(".dstlod")) {
                    relative = root.relativize(file);
                    String leaf = relative.getFileName().toString();
                    relative = relative.resolveSibling(leaf.substring(0, leaf.length() - ".dstlod".length()));
                } else {
                    continue;
                }
                ResourceLocation id = resourceId(relative);
                if (id == null) {
                    errors.add("Invalid LOD package path: " + file);
                } else {
                    ids.add(id);
                }
            }
        } catch (IOException e) {
            errors.add("Could not scan " + root + ": " + rootMessage(e));
        }
    }

    private static ResourceLocation resourceId(Path relative) {
        if (relative.getNameCount() < 2) {
            return null;
        }
        String namespace = relative.getName(0).toString();
        StringBuilder path = new StringBuilder();
        for (int index = 1; index < relative.getNameCount(); index++) {
            if (!path.isEmpty()) {
                path.append('/');
            }
            path.append(relative.getName(index));
        }
        return ResourceLocation.tryParse(namespace + ':' + path);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static void write(MinecraftServer server, Map<String, LodPackageOffer> offers) throws IOException {
        Path target = path(server);
        Files.createDirectories(target.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", FORMAT_VERSION);
        JsonObject packs = new JsonObject();
        offers.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            LodPackageOffer offer = entry.getValue();
            JsonObject value = new JsonObject();
            value.addProperty("delivery", offer.delivery().name().toLowerCase(java.util.Locale.ROOT));
            if (!offer.serverHosted()) {
                value.addProperty("url", offer.url());
            }
            value.addProperty("bytes", offer.bytes());
            value.addProperty("sha256", offer.sha256());
            if (offer.serverHosted()) {
                value.addProperty("serverHosted", true);
            }
            packs.add(entry.getKey(), value);
        });
        root.add("packs", packs);
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private record LocalArchiveState(long bytes, long modified, String sha256) {
    }

    public record HostedArchive(Path path, LodPackageOffer offer, boolean generated) {
    }

    public record ReloadResult(int hostedPacks, int generatedArchives, List<String> errors) {
    }

    record ResourceRoot(Path packages, Path generatedArchives) {
        ResourceRoot {
            packages = packages.toAbsolutePath().normalize();
            generatedArchives = generatedArchives.toAbsolutePath().normalize();
        }
    }
}
