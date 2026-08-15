package vibe.liteming.dynamicstage.lod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/** Server-side catalog of downloadable LOD archives. */
public final class LodDistributionStore {
    private static final int FORMAT_VERSION = 1;
    private static final long MAX_FILE_BYTES = 2L * 1024L * 1024L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LodDistributionStore() {
    }

    public static LodPackageOffer find(MinecraftServer server, ResourceLocation id) {
        if (server == null || id == null) {
            return null;
        }
        try {
            return read(server).get(id.toString());
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
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
            offers.put(id.toString(), new LodPackageOffer(mode,
                    value.get("url").getAsString(), value.get("bytes").getAsLong(),
                    value.get("sha256").getAsString()));
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
            value.addProperty("url", offer.url());
            value.addProperty("bytes", offer.bytes());
            value.addProperty("sha256", offer.sha256());
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
}
