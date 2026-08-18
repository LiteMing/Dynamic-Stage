package vibe.liteming.dynamicstage.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import dev.architectury.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Common server configuration shared by dedicated and integrated servers. */
public final class StageServerConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(StageServerConfig.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final boolean DEFAULT_GUIDE_MESSAGES = true;

    private static volatile boolean guideMessages = DEFAULT_GUIDE_MESSAGES;
    private static boolean loaded;

    private StageServerConfig() {
    }

    public static boolean guideMessages() {
        if (!loaded) {
            reload();
        }
        return guideMessages;
    }

    public static synchronized void reload() {
        Path path = path();
        try {
            if (!Files.isRegularFile(path)) {
                write(path, DEFAULT_GUIDE_MESSAGES);
            }
            JsonObject root = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
            if (root == null) {
                throw new IOException("empty server config");
            }
            guideMessages = root.has("guide_messages")
                    ? root.get("guide_messages").getAsBoolean() : DEFAULT_GUIDE_MESSAGES;
        } catch (IOException | JsonParseException | IllegalStateException e) {
            guideMessages = DEFAULT_GUIDE_MESSAGES;
            LOGGER.warn("Could not load Dynamic Stage server config {}: {}", path, e.getMessage());
        }
        loaded = true;
    }

    public static synchronized void setGuideMessages(boolean enabled) throws IOException {
        write(path(), enabled);
        guideMessages = enabled;
        loaded = true;
    }

    private static void write(Path path, boolean enabled) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("server config path has no parent");
        }
        Files.createDirectories(parent);
        JsonObject root = new JsonObject();
        root.addProperty("guide_messages", enabled);
        Path temporary = Files.createTempFile(parent, "dynamicstage-server", ".tmp");
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

    private static Path path() {
        return Platform.getConfigFolder().resolve("dynamicstage-server.json").toAbsolutePath().normalize();
    }
}
