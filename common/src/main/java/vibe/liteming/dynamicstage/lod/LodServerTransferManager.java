package vibe.liteming.dynamicstage.lod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;
import vibe.liteming.dynamicstage.network.LodDownloadChunkPacket;
import vibe.liteming.dynamicstage.network.LodDownloadRequestPacket;
import vibe.liteming.dynamicstage.network.LodDownloadResultPacket;
import vibe.liteming.dynamicstage.stage.StageSessionManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Tick-paced server-to-client streaming for archives stored in the world. */
public final class LodServerTransferManager {
    private static final int CHUNKS_PER_TICK = 4;
    private static final Map<TransferKey, Transfer> ACTIVE = new ConcurrentHashMap<>();

    private LodServerTransferManager() {
    }

    public static void request(ServerPlayer player, LodDownloadRequestPacket request) {
        MinecraftServer server = player.getServer();
        LodPackageOffer offer = server == null ? null : LodDistributionStore.find(server, request.lodPackId());
        if (server == null || offer == null || !offer.serverHosted()
                || !offer.sha256().equals(request.sha256())
                || !StageSessionManager.canRequestLodDownload(player, request.lodPackId())) {
            sendFailure(player, request.lodPackId(), request.sha256(), "Server LOD package is unavailable");
            return;
        }
        try {
            LodDistributionStore.HostedArchive hosted = LodDistributionStore.hostedArchive(
                    server, request.lodPackId());
            if (hosted == null || !hosted.offer().sha256().equals(offer.sha256())) {
                throw new IOException("Server LOD archive changed or is missing");
            }
            Path archive = hosted.path();
            if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(archive) != offer.bytes()) {
                throw new IOException("Server LOD archive changed or is missing");
            }
            long offset = Math.min(request.offset(), offer.bytes());
            TransferKey key = new TransferKey(player.getUUID(), request.lodPackId());
            Transfer previous = ACTIVE.remove(key);
            if (previous != null) {
                previous.close();
            }
            InputStream input = Files.newInputStream(archive);
            skipFully(input, offset);
            ACTIVE.put(key, new Transfer(key, player.getUUID(), request.lodPackId(), offer.sha256(),
                    offer.bytes(), input, offset));
        } catch (IOException | RuntimeException e) {
            sendFailure(player, request.lodPackId(), request.sha256(), rootMessage(e));
        }
    }

    public static void tick(MinecraftServer server) {
        for (Transfer transfer : ACTIVE.values()) {
            ServerPlayer player = server.getPlayerList().getPlayer(transfer.playerId());
            if (player == null) {
                if (ACTIVE.remove(transfer.key(), transfer)) {
                    transfer.close();
                }
                continue;
            }
            try {
                for (int index = 0; index < CHUNKS_PER_TICK; index++) {
                    int length = (int) Math.min(LodDownloadChunkPacket.MAX_CHUNK_BYTES,
                            transfer.bytes() - transfer.offset());
                    if (length <= 0) {
                        finish(server, transfer, player);
                        break;
                    }
                    byte[] data = transfer.input().readNBytes(length);
                    if (data.length != length) {
                        fail(transfer, player, "Server LOD archive ended before its declared size");
                        break;
                    }
                    long offset = transfer.offset();
                    transfer.advance(length);
                    boolean complete = transfer.offset() == transfer.bytes();
                    DynamicStageNetwork.sendLodDownloadChunk(player,
                            new LodDownloadChunkPacket(transfer.lodPackId(), transfer.sha256(), offset,
                                    data, complete));
                    if (complete) {
                        finish(server, transfer, player);
                        break;
                    }
                }
            } catch (IOException | RuntimeException e) {
                fail(transfer, player, rootMessage(e));
            }
        }
    }

    public static void clear() {
        ACTIVE.values().forEach(Transfer::close);
        ACTIVE.clear();
    }

    public static void cancel(UUID playerId) {
        ACTIVE.entrySet().removeIf(entry -> {
            if (!entry.getKey().playerId().equals(playerId)) {
                return false;
            }
            entry.getValue().close();
            return true;
        });
    }

    private static void finish(MinecraftServer server, Transfer transfer, ServerPlayer player) {
        if (ACTIVE.remove(transfer.key(), transfer)) {
            transfer.close();
            DynamicStageNetwork.sendLodDownloadResult(player,
                    new LodDownloadResultPacket(transfer.lodPackId(), transfer.sha256(), true, ""));
        }
    }

    private static void fail(Transfer transfer, ServerPlayer player, String error) {
        if (ACTIVE.remove(transfer.key(), transfer)) {
            transfer.close();
            sendFailure(player, transfer.lodPackId(), transfer.sha256(), error);
        }
    }

    private static void sendFailure(ServerPlayer player, ResourceLocation id, String sha256, String error) {
        try {
            DynamicStageNetwork.sendLodDownloadResult(player,
                    new LodDownloadResultPacket(id, sha256, false, error));
        } catch (RuntimeException ignored) {
        }
    }

    private static void skipFully(InputStream input, long offset) throws IOException {
        long remaining = offset;
        while (remaining > 0L) {
            long skipped = input.skip(remaining);
            if (skipped > 0L) {
                remaining -= skipped;
                continue;
            }
            if (input.read() < 0) {
                throw new IOException("Server LOD archive cannot resume at the requested offset");
            }
            remaining--;
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null || current.getMessage().isBlank()
                ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record TransferKey(UUID playerId, ResourceLocation lodPackId) {
    }

    private static final class Transfer {
        private final TransferKey key;
        private final UUID playerId;
        private final ResourceLocation lodPackId;
        private final String sha256;
        private final long bytes;
        private final InputStream input;
        private long offset;

        private Transfer(TransferKey key, UUID playerId, ResourceLocation lodPackId, String sha256,
                         long bytes, InputStream input, long offset) {
            this.key = key;
            this.playerId = playerId;
            this.lodPackId = lodPackId;
            this.sha256 = sha256;
            this.bytes = bytes;
            this.input = input;
            this.offset = offset;
        }

        private void advance(long amount) {
            offset += amount;
        }

        private void close() {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }

        private TransferKey key() { return key; }
        private UUID playerId() { return playerId; }
        private ResourceLocation lodPackId() { return lodPackId; }
        private String sha256() { return sha256; }
        private long bytes() { return bytes; }
        private InputStream input() { return input; }
        private long offset() { return offset; }
    }
}
