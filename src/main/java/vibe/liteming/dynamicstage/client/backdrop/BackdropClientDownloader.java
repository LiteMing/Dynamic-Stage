package vibe.liteming.dynamicstage.client.backdrop;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.backdrop.BackdropBlobIO;
import vibe.liteming.dynamicstage.backdrop.BackdropProducts;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.network.BackdropChunkPacket;
import vibe.liteming.dynamicstage.network.BackdropDistribution;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Resumable sequential client download with content and Blob validation. */
public final class BackdropClientDownloader {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackdropClientDownloader.class);
    private static final long MAX_BYTES = BackdropProducts.MAX_PRODUCT_BYTES;
    private static final int RETRY_TICKS = 100;
    private static final AtomicLong LOAD_SEQUENCE = new AtomicLong();

    @Nullable
    private static Session session;

    private BackdropClientDownloader() {
    }

    public static void prepare(ClientStageSession.Snapshot snapshot) {
        clearDownload(false);
        BackdropRenderer.clearBackdrop();
        if (!BackdropProducts.isSha256(snapshot.backdropHash())
                || snapshot.backdropBytes() <= 0 || snapshot.backdropBytes() > MAX_BYTES) {
            LOGGER.warn("Rejected invalid backdrop manifest for {}", snapshot.stageId());
            BackdropRenderer.clearBackdrop();
            return;
        }
        try {
            Path complete = BackdropCache.complete(snapshot.stageId(), snapshot.backdropHash());
            if (Files.isRegularFile(complete) && Files.size(complete) == snapshot.backdropBytes()) {
                loadAndActivate(snapshot, complete);
                return;
            }
            Files.deleteIfExists(complete);
            Path partial = BackdropCache.partial(snapshot.stageId(), snapshot.backdropHash());
            long usable = preparePartial(partial, snapshot.backdropBytes());
            Session next = new Session(snapshot, partial, (int) (usable / BackdropDistribution.CHUNK_SIZE));
            session = next;
            if (usable == snapshot.backdropBytes()) {
                finish(next);
            } else {
                request(next);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to prepare backdrop cache: {}", e.getMessage());
            BackdropRenderer.clearBackdrop();
        }
    }

    public static void onChunk(BackdropChunkPacket packet) {
        Session current = session;
        if (current == null || !current.snapshot.stageId().equals(packet.stageId())
                || !current.snapshot.backdropHash().equals(packet.backdropHash())
                || packet.index() != current.nextIndex || !current.awaiting
                || packet.data().length <= 0 || packet.data().length > BackdropDistribution.CHUNK_SIZE) {
            return;
        }
        long expectedOffset = (long) current.nextIndex * BackdropDistribution.CHUNK_SIZE;
        long expectedLength = Math.min(BackdropDistribution.CHUNK_SIZE,
                current.snapshot.backdropBytes() - expectedOffset);
        if (expectedLength != packet.data().length
                || packet.last() != (expectedOffset + expectedLength == current.snapshot.backdropBytes())) {
            fail(current, "server sent an invalid chunk boundary");
            return;
        }
        try (FileChannel channel = FileChannel.open(current.partial,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            channel.position(expectedOffset);
            java.nio.ByteBuffer data = java.nio.ByteBuffer.wrap(packet.data());
            while (data.hasRemaining()) {
                channel.write(data);
            }
            channel.truncate(expectedOffset + expectedLength);
        } catch (IOException e) {
            fail(current, e.getMessage());
            return;
        }
        current.awaiting = false;
        current.nextIndex++;
        if (packet.last()) {
            finish(current);
        } else {
            request(current);
        }
    }

    public static void tick() {
        Session current = session;
        if (current != null && current.awaiting && ++current.waitTicks >= RETRY_TICKS) {
            current.awaiting = false;
            request(current);
        }
    }

    public static void clear() {
        clearDownload(true);
    }

    private static long preparePartial(Path partial, long expectedBytes) throws IOException {
        if (!Files.exists(partial)) {
            return 0L;
        }
        long size = Files.size(partial);
        if (size < 0 || size > expectedBytes) {
            Files.delete(partial);
            return 0L;
        }
        if (size == expectedBytes) {
            return size;
        }
        long usable = size - size % BackdropDistribution.CHUNK_SIZE;
        if (usable != size) {
            try (FileChannel channel = FileChannel.open(partial, StandardOpenOption.WRITE)) {
                channel.truncate(usable);
            }
        }
        return usable;
    }

    private static void request(Session current) {
        current.awaiting = true;
        current.waitTicks = 0;
        DynamicStageNetwork.requestBackdrop(current.snapshot.stageId(),
                current.snapshot.backdropHash(), current.nextIndex);
    }

    private static void finish(Session current) {
        session = null;
        try {
            byte[] bytes = Files.readAllBytes(current.partial);
            if (bytes.length != current.snapshot.backdropBytes()
                    || !BackdropProducts.sha256Hex(bytes).equals(current.snapshot.backdropHash())) {
                Files.deleteIfExists(current.partial);
                throw new IOException("downloaded backdrop hash mismatch");
            }
            BackdropBlobIO.Blob blob = BackdropBlobIO.read(bytes);
            if (!current.snapshot.stageId().equals(blob.manifest().getStageId())) {
                Files.deleteIfExists(current.partial);
                throw new IOException("downloaded backdrop stage id mismatch");
            }
            Path complete = BackdropCache.complete(current.snapshot.stageId(), current.snapshot.backdropHash());
            try {
                Files.move(current.partial, complete, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(current.partial, complete, StandardCopyOption.REPLACE_EXISTING);
            }
            loadAndActivate(current.snapshot, complete);
        } catch (IOException | IllegalArgumentException e) {
            fail(current, e.getMessage());
        }
    }

    private static void loadAndActivate(ClientStageSession.Snapshot snapshot, Path file) {
        long request = LOAD_SEQUENCE.incrementAndGet();
        CompletableFuture.runAsync(() -> {
            try {
                byte[] bytes = Files.readAllBytes(file);
                if (!BackdropProducts.sha256Hex(bytes).equals(snapshot.backdropHash())) {
                    Files.deleteIfExists(file);
                    Minecraft.getInstance().execute(() -> prepare(snapshot));
                    return;
                }
                BackdropBlobIO.Blob blob = BackdropBlobIO.read(bytes);
                if (!snapshot.stageId().equals(blob.manifest().getStageId())) {
                    throw new IllegalArgumentException("cached backdrop stage id mismatch");
                }
                Minecraft.getInstance().execute(() -> {
                    ClientStageSession.Snapshot active = ClientStageSession.active();
                    if (request == LOAD_SEQUENCE.get() && snapshot.equals(active)) {
                        BackdropRenderer.setBlob(blob, snapshot.stageOrigin());
                    }
                });
            } catch (IOException | RuntimeException e) {
                LOGGER.warn("Failed to load cached backdrop {}: {}", file, e.getMessage());
                boolean removed = false;
                try {
                    Files.deleteIfExists(file);
                    removed = !Files.exists(file);
                } catch (IOException ignored) {
                }
                boolean retry = removed;
                Minecraft.getInstance().execute(() -> {
                    if (retry && snapshot.equals(ClientStageSession.active())) {
                        prepare(snapshot);
                    }
                });
            }
        });
    }

    private static void clearDownload(boolean clearRenderer) {
        session = null;
        LOAD_SEQUENCE.incrementAndGet();
        if (clearRenderer) {
            BackdropRenderer.clearBackdrop();
        }
    }

    private static void fail(Session current, String reason) {
        if (session == current) {
            session = null;
        }
        LOGGER.warn("Backdrop download failed for {}: {}", current.snapshot.stageId(), reason);
        try {
            Files.deleteIfExists(current.partial);
        } catch (IOException ignored) {
        }
        BackdropRenderer.clearBackdrop();
    }

    private static final class Session {
        private final ClientStageSession.Snapshot snapshot;
        private final Path partial;
        private int nextIndex;
        private boolean awaiting;
        private int waitTicks;

        private Session(ClientStageSession.Snapshot snapshot, Path partial, int nextIndex) {
            this.snapshot = snapshot;
            this.partial = partial;
            this.nextIndex = nextIndex;
        }
    }
}
