package vibe.liteming.dynamicstage.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.stage.StageBoundary;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Local-only render preferences; this file is never synchronized by the server. */
public final class StageClientConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(StageClientConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double DEFAULT_VISIBLE_DISTANCE = 10.0D;
    private static final float DEFAULT_OPACITY = 1.0F;
    private static final boolean DEFAULT_ALLOW_SERVER_LOD_DOWNLOADS = true;
    private static final int DEFAULT_MAX_SERVER_LOD_DOWNLOAD_MIB = 256;
    private static final int MAX_SERVER_LOD_DOWNLOAD_MIB = 512;

    private static volatile BoundaryDisplay boundary = BoundaryDisplay.defaults();
    private static volatile boolean allowServerLodDownloads = DEFAULT_ALLOW_SERVER_LOD_DOWNLOADS;
    private static volatile int maxServerLodDownloadMib = DEFAULT_MAX_SERVER_LOD_DOWNLOAD_MIB;
    private static boolean loaded;

    private StageClientConfig() {
    }

    public static BoundaryDisplay boundary() {
        if (!loaded) {
            reload();
        }
        return boundary;
    }

    public static boolean allowServerLodDownloads() {
        if (!loaded) {
            reload();
        }
        return allowServerLodDownloads;
    }

    public static int maxServerLodDownloadMib() {
        if (!loaded) {
            reload();
        }
        return maxServerLodDownloadMib;
    }

    public static long maxServerLodDownloadBytes() {
        return maxServerLodDownloadMib() * 1024L * 1024L;
    }

    public static synchronized void reload() {
        Path path = configPath();
        try {
            if (!Files.isRegularFile(path)) {
                writeDefaults(path);
            }
            Settings settings = readSettings(path);
            boundary = settings.boundary();
            allowServerLodDownloads = settings.allowServerLodDownloads();
            maxServerLodDownloadMib = settings.maxServerLodDownloadMib();
        } catch (IOException | RuntimeException e) {
            boundary = BoundaryDisplay.defaults();
            allowServerLodDownloads = DEFAULT_ALLOW_SERVER_LOD_DOWNLOADS;
            maxServerLodDownloadMib = DEFAULT_MAX_SERVER_LOD_DOWNLOAD_MIB;
            LOGGER.warn("Could not load Dynamic Stage client config {}: {}", path, e.getMessage());
        }
        loaded = true;
    }

    public static BoundaryDisplay createBoundaryDisplay(double visibleDistance, float opacity, String color) {
        return new BoundaryDisplay(visibleDistance, opacity, parseColor(color));
    }

    public static synchronized void saveBoundary(BoundaryDisplay display) throws IOException {
        save(display, allowServerLodDownloads(), maxServerLodDownloadMib());
    }

    public static synchronized void saveServerLodDownloads(boolean allow, int maxMib) throws IOException {
        save(boundary(), allow, maxMib);
    }

    public static synchronized void save(BoundaryDisplay display, boolean allow, int maxMib) throws IOException {
        Settings settings = new Settings(display, allow, maxMib);
        writeSettings(configPath(), settings);
        boundary = settings.boundary();
        allowServerLodDownloads = settings.allowServerLodDownloads();
        maxServerLodDownloadMib = settings.maxServerLodDownloadMib();
        loaded = true;
    }

    static BoundaryDisplay read(Path path) throws IOException {
        return readSettings(path).boundary();
    }

    static Settings readSettings(Path path) throws IOException {
        JsonObject root;
        try {
            root = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
        } catch (JsonParseException e) {
            throw new IOException("invalid JSON", e);
        }
        if (root == null) {
            throw new IOException("empty client config");
        }
        double visibleDistance = root.has("boundary_visible_distance")
                ? root.get("boundary_visible_distance").getAsDouble() : DEFAULT_VISIBLE_DISTANCE;
        float opacity = root.has("boundary_opacity")
                ? root.get("boundary_opacity").getAsFloat() : DEFAULT_OPACITY;
        String color = root.has("boundary_fallback_color")
                ? root.get("boundary_fallback_color").getAsString()
                : root.has("boundary_color") ? root.get("boundary_color").getAsString() : "default";
        boolean allowDownloads = root.has("allow_server_lod_downloads")
                ? root.get("allow_server_lod_downloads").getAsBoolean()
                : DEFAULT_ALLOW_SERVER_LOD_DOWNLOADS;
        int maxDownloads = root.has("max_server_lod_download_mib")
                ? root.get("max_server_lod_download_mib").getAsInt()
                : DEFAULT_MAX_SERVER_LOD_DOWNLOAD_MIB;
        return new Settings(new BoundaryDisplay(visibleDistance, opacity, parseColor(color)),
                allowDownloads, maxDownloads);
    }

    private static void writeDefaults(Path path) throws IOException {
        write(path, BoundaryDisplay.defaults());
    }

    static void write(Path path, BoundaryDisplay display) throws IOException {
        writeSettings(path, new Settings(display, DEFAULT_ALLOW_SERVER_LOD_DOWNLOADS,
                DEFAULT_MAX_SERVER_LOD_DOWNLOAD_MIB));
    }

    static void writeSettings(Path path, Settings settings) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("client config path has no parent");
        }
        Files.createDirectories(parent);
        JsonObject root = new JsonObject();
        root.addProperty("boundary_visible_distance", settings.boundary().visibleDistance());
        root.addProperty("boundary_opacity", settings.boundary().opacity());
        root.addProperty("boundary_fallback_color", settings.boundary().colorSetting());
        root.addProperty("allow_server_lod_downloads", settings.allowServerLodDownloads());
        root.addProperty("max_server_lod_download_mib", settings.maxServerLodDownloadMib());
        Path temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Path configPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config")
                .resolve("dynamicstage-client.json").toAbsolutePath().normalize();
    }

    @Nullable
    private static Integer parseColor(String value) {
        if (value == null || value.equalsIgnoreCase("default") || value.equalsIgnoreCase("stage")) {
            return null;
        }
        String digits = value.startsWith("#") ? value.substring(1)
                : value.toLowerCase(Locale.ROOT).startsWith("0x") ? value.substring(2) : value;
        if (!digits.matches("[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException("boundary_fallback_color must be 'default' or a six-digit RGB value");
        }
        return Integer.parseInt(digits, 16);
    }

    public record BoundaryDisplay(double visibleDistance, float opacity, @Nullable Integer fallbackColor) {
        public BoundaryDisplay {
            if (!Double.isFinite(visibleDistance) || visibleDistance < 0.0D || visibleDistance > 128.0D) {
                throw new IllegalArgumentException("boundary_visible_distance must be between 0 and 128");
            }
            if (!Float.isFinite(opacity) || opacity < 0.0F || opacity > 1.0F) {
                throw new IllegalArgumentException("boundary_opacity must be between 0 and 1");
            }
            if (fallbackColor != null && (fallbackColor < 0 || fallbackColor > 0xFFFFFF)) {
                throw new IllegalArgumentException("invalid boundary color");
            }
        }

        public static BoundaryDisplay defaults() {
            return new BoundaryDisplay(DEFAULT_VISIBLE_DISTANCE, DEFAULT_OPACITY, null);
        }

        public int color(int stageColor) {
            if (stageColor != StageBoundary.UNSET_COLOR) {
                return stageColor;
            }
            return fallbackColor == null ? StageBoundary.DEFAULT_COLOR : fallbackColor;
        }

        public String colorSetting() {
            return fallbackColor == null ? "default" : String.format(Locale.ROOT, "%06X", fallbackColor);
        }
    }

    record Settings(BoundaryDisplay boundary, boolean allowServerLodDownloads, int maxServerLodDownloadMib) {
        Settings {
            if (boundary == null) {
                throw new IllegalArgumentException("boundary settings are required");
            }
            if (maxServerLodDownloadMib < 1 || maxServerLodDownloadMib > MAX_SERVER_LOD_DOWNLOAD_MIB) {
                throw new IllegalArgumentException("max_server_lod_download_mib must be between 1 and "
                        + MAX_SERVER_LOD_DOWNLOAD_MIB);
            }
        }
    }
}
