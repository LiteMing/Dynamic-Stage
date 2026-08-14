package vibe.liteming.dynamicstage.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Local-only render preferences; this file is never synchronized by the server. */
public final class StageClientConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(StageClientConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final double DEFAULT_VISIBLE_DISTANCE = 10.0D;
    private static final float DEFAULT_OPACITY = 1.0F;

    private static volatile BoundaryDisplay boundary = BoundaryDisplay.defaults();
    private static boolean loaded;

    private StageClientConfig() {
    }

    public static BoundaryDisplay boundary() {
        if (!loaded) {
            reload();
        }
        return boundary;
    }

    public static synchronized void reload() {
        Path path = configPath();
        try {
            if (!Files.isRegularFile(path)) {
                writeDefaults(path);
            }
            boundary = read(path);
        } catch (IOException | RuntimeException e) {
            boundary = BoundaryDisplay.defaults();
            LOGGER.warn("Could not load Dynamic Stage client config {}: {}", path, e.getMessage());
        }
        loaded = true;
    }

    static BoundaryDisplay read(Path path) throws IOException {
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
        String color = root.has("boundary_color") ? root.get("boundary_color").getAsString() : "stage";
        return new BoundaryDisplay(visibleDistance, opacity, parseColor(color));
    }

    private static void writeDefaults(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        JsonObject root = new JsonObject();
        root.addProperty("boundary_visible_distance", DEFAULT_VISIBLE_DISTANCE);
        root.addProperty("boundary_opacity", DEFAULT_OPACITY);
        root.addProperty("boundary_color", "stage");
        Files.writeString(path, GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private static Path configPath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config")
                .resolve("dynamicstage-client.json").toAbsolutePath().normalize();
    }

    @Nullable
    private static Integer parseColor(String value) {
        if (value == null || value.equalsIgnoreCase("stage")) {
            return null;
        }
        String digits = value.startsWith("#") ? value.substring(1)
                : value.toLowerCase(Locale.ROOT).startsWith("0x") ? value.substring(2) : value;
        if (!digits.matches("[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException("boundary_color must be 'stage' or a six-digit RGB value");
        }
        return Integer.parseInt(digits, 16);
    }

    public record BoundaryDisplay(double visibleDistance, float opacity, @Nullable Integer colorOverride) {
        public BoundaryDisplay {
            if (!Double.isFinite(visibleDistance) || visibleDistance < 0.0D || visibleDistance > 128.0D) {
                throw new IllegalArgumentException("boundary_visible_distance must be between 0 and 128");
            }
            if (!Float.isFinite(opacity) || opacity < 0.0F || opacity > 1.0F) {
                throw new IllegalArgumentException("boundary_opacity must be between 0 and 1");
            }
            if (colorOverride != null && (colorOverride < 0 || colorOverride > 0xFFFFFF)) {
                throw new IllegalArgumentException("invalid boundary color");
            }
        }

        public static BoundaryDisplay defaults() {
            return new BoundaryDisplay(DEFAULT_VISIBLE_DISTANCE, DEFAULT_OPACITY, null);
        }

        public int color(int stageColor) {
            return colorOverride == null ? stageColor : colorOverride;
        }
    }
}
