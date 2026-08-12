package vibe.liteming.dynamicstage.network;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.backdrop.BackdropProducts;
import vibe.liteming.dynamicstage.stage.StageSession;
import vibe.liteming.dynamicstage.stage.StageSessionManager;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Validates and serves content-addressed backdrop chunks for active sessions. */
public final class BackdropDistribution {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackdropDistribution.class);
    public static final int CHUNK_SIZE = 16 * 1024;
    private static final int MAX_REQUESTS_PER_TICK = 8;
    private static final ConcurrentHashMap<UUID, RequestWindow> REQUEST_WINDOWS = new ConcurrentHashMap<>();

    private BackdropDistribution() {
    }

    public static void sendChunk(ServerPlayer player, String stageId, String hash, int index) {
        if (index < 0 || !BackdropProducts.isSha256(hash) || !StageWorlds.isStageLevel(player.level())) {
            return;
        }
        if (!allowRequest(player)) {
            return;
        }
        Optional<StageSession> found = StageSessionManager.get(player);
        if (found.isEmpty()) {
            return;
        }
        StageSession session = found.get();
        if (!session.stageId().equals(stageId) || !session.backdropHash().equals(hash)) {
            return;
        }
        long start;
        try {
            start = Math.multiplyExact((long) index, CHUNK_SIZE);
        } catch (ArithmeticException e) {
            return;
        }
        if (start >= session.backdropBytes()) {
            return;
        }
        Path product = BackdropProducts.resolve(player.getServer(), stageId, hash);
        if (product == null) {
            return;
        }
        int length = (int) Math.min(CHUNK_SIZE, session.backdropBytes() - start);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (FileChannel channel = FileChannel.open(product, StandardOpenOption.READ)) {
            if (channel.size() != session.backdropBytes()) {
                return;
            }
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, start + buffer.position());
                if (read <= 0) {
                    break;
                }
            }
            if (buffer.hasRemaining()) {
                return;
            }
            boolean last = start + length == session.backdropBytes();
            DynamicStageNetwork.sendChunk(player,
                    new BackdropChunkPacket(stageId, hash, index, buffer.array(), last));
        } catch (IOException e) {
            LOGGER.warn("Failed to serve backdrop chunk {} for {}: {}", index, player.getUUID(), e.getMessage());
        }
    }

    public static void clearPlayer(UUID playerId) {
        REQUEST_WINDOWS.remove(playerId);
    }

    public static void clearAll() {
        REQUEST_WINDOWS.clear();
    }

    private static boolean allowRequest(ServerPlayer player) {
        long tick = player.getServer().getTickCount();
        RequestWindow window = REQUEST_WINDOWS.computeIfAbsent(player.getUUID(), ignored -> new RequestWindow());
        if (window.tick != tick) {
            window.tick = tick;
            window.requests = 0;
        }
        return ++window.requests <= MAX_REQUESTS_PER_TICK;
    }

    private static final class RequestWindow {
        private long tick = Long.MIN_VALUE;
        private int requests;
    }
}
