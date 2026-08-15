package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.client.config.StageClientConfig;
import vibe.liteming.dynamicstage.lod.LodPackageOffer;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;
import vibe.liteming.dynamicstage.network.LodDownloadChunkPacket;
import vibe.liteming.dynamicstage.network.LodDownloadRequestPacket;
import vibe.liteming.dynamicstage.network.LodDownloadResultPacket;
import vibe.liteming.dynamicstage.util.ContentHash;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads server-offered LOD archives outside the render and network threads. */
public final class LodPackDownloadManager {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Dynamic Stage LOD Downloader");
        thread.setDaemon(true);
        return thread;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Map<ResourceLocation, CompletableFuture<Result>> ACTIVE = new ConcurrentHashMap<>();
    private static final Map<ResourceLocation, ServerTransfer> SERVER_TRANSFERS = new ConcurrentHashMap<>();

    private LodPackDownloadManager() {
    }

    public static CompletableFuture<Result> prepare(ResourceLocation id, LodPackageOffer offer) {
        try {
            LodPackRegistry.load(id);
            return CompletableFuture.completedFuture(Result.ready());
        } catch (LodPackRegistry.UnavailableException unavailable) {
            if (offer == null) {
                return CompletableFuture.completedFuture(Result.optional(unavailable.getMessage()));
            }
        } catch (IOException e) {
            return CompletableFuture.completedFuture(Result.failed("Invalid local LOD package: " + e.getMessage()));
        }
        Result rejected = rejectByClientPolicy(offer);
        if (rejected != null) {
            return CompletableFuture.completedFuture(rejected);
        }
        CompletableFuture<Result> existing = ACTIVE.get(id);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Result> created = new CompletableFuture<>();
        existing = ACTIVE.putIfAbsent(id, created);
        if (existing != null) {
            return existing;
        }
        if (offer.serverHosted()) {
            startServerDownload(id, offer, created);
        } else {
            CompletableFuture.supplyAsync(() -> download(id, offer), EXECUTOR)
                    .whenComplete((result, error) -> complete(created, result, error));
        }
        created.whenComplete((result, error) -> ACTIVE.remove(id, created));
        return created;
    }

    private static void complete(CompletableFuture<Result> target, Result result, Throwable error) {
        if (error != null) {
            target.complete(Result.failed(rootMessage(error)));
        } else {
            target.complete(result);
        }
    }

    private static Result rejectByClientPolicy(LodPackageOffer offer) {
        return rejectByClientPolicy(offer, StageClientConfig.allowServerLodDownloads(),
                StageClientConfig.maxServerLodDownloadBytes(), StageClientConfig.maxServerLodDownloadMib());
    }

    static Result rejectByClientPolicy(LodPackageOffer offer, boolean allow, long maxBytes, int maxMib) {
        String reason = null;
        if (!allow) {
            reason = "Server LOD downloads are disabled by the client";
        } else if (offer.bytes() > maxBytes) {
            reason = "Server LOD package is larger than the client limit of "
                    + maxMib + " MiB";
        }
        if (reason == null) {
            return null;
        }
        return offer.required() ? Result.failed(reason) : Result.optional(reason);
    }

    private static Result download(ResourceLocation id, LodPackageOffer offer) {
        message("Downloading stage LOD package '" + id + "' ("
                + String.format(java.util.Locale.ROOT, "%.1f", offer.bytes() / 1048576.0D) + " MiB)...");
        try {
            Path downloadDirectory = downloadDirectory();
            Files.createDirectories(downloadDirectory);
            Path archive = downloadDirectory.resolve(offer.sha256() + ".dstlod");
            if (!validArchive(archive, offer)) {
                Path partial = downloadDirectory.resolve(offer.sha256() + ".dstlod.part");
                fetch(offer, partial);
                if (Files.size(partial) != offer.bytes()) {
                    throw new IOException("Downloaded size does not match the server manifest");
                }
                String hash = ContentHash.sha256Hex(partial);
                if (!offer.sha256().equals(hash)) {
                    Files.deleteIfExists(partial);
                    throw new IOException("Downloaded SHA-256 does not match the server manifest");
                }
                Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING);
            }
            return installArchive(id, offer, archive);
        } catch (Exception e) {
            String error = rootMessage(e);
            return offer.required() ? Result.failed(error) : Result.optional(error);
        }
    }

    private static void startServerDownload(ResourceLocation id, LodPackageOffer offer,
                                            CompletableFuture<Result> result) {
        try {
            Path downloadDirectory = downloadDirectory();
            Files.createDirectories(downloadDirectory);
            Path archive = downloadDirectory.resolve(offer.sha256() + ".dstlod");
            if (validArchive(archive, offer)) {
                result.complete(installArchive(id, offer, archive));
                return;
            }
            Path partial = downloadDirectory.resolve(offer.sha256() + ".dstlod.part");
            long existing = Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS) ? Files.size(partial) : 0L;
            if (existing < 0L || existing > offer.bytes()) {
                Files.deleteIfExists(partial);
                existing = 0L;
            }
            ServerTransfer transfer = new ServerTransfer(id, offer, partial, result, existing);
            ServerTransfer previous = SERVER_TRANSFERS.putIfAbsent(id, transfer);
            if (previous != null) {
                result.complete(Result.failed("Another server LOD transfer is already active"));
                return;
            }
            message("Receiving stage LOD package '" + id + "' from the server ("
                    + String.format(java.util.Locale.ROOT, "%.1f", offer.bytes() / 1048576.0D) + " MiB)...");
            DynamicStageNetwork.requestLodDownload(new LodDownloadRequestPacket(id, offer.sha256(), existing));
        } catch (Exception e) {
            result.complete(offer.required() ? Result.failed(rootMessage(e)) : Result.optional(rootMessage(e)));
        }
    }

    public static void acceptServerChunk(LodDownloadChunkPacket packet) {
        ServerTransfer transfer = SERVER_TRANSFERS.get(packet.lodPackId());
        if (transfer != null && transfer.offer.sha256().equals(packet.sha256())) {
            EXECUTOR.execute(() -> transfer.acceptChunk(packet));
        }
    }

    public static void acceptServerResult(LodDownloadResultPacket packet) {
        ServerTransfer transfer = SERVER_TRANSFERS.get(packet.lodPackId());
        if (transfer != null && transfer.offer.sha256().equals(packet.sha256())) {
            EXECUTOR.execute(() -> transfer.acceptResult(packet));
        }
    }

    public static void disconnect() {
        SERVER_TRANSFERS.forEach((id, transfer) -> {
            if (SERVER_TRANSFERS.remove(id, transfer)) {
                EXECUTOR.execute(() -> transfer.fail("Disconnected from the server"));
            }
        });
    }

    private static Path downloadDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("dynamicstage")
                .resolve("downloads").toAbsolutePath().normalize();
    }

    private static Result installArchive(ResourceLocation id, LodPackageOffer offer, Path archive) throws IOException {
        long extractionLimit = Math.min(2L * 1024L * 1024L * 1024L,
                Math.max(64L * 1024L * 1024L, offer.bytes() * 8L));
        LodPackArchive.install(archive, LodPackRegistry.rootDirectory(), id, extractionLimit);
        LodPackRegistry.load(id);
        message("Installed stage LOD package '" + id + "'.");
        return Result.ready();
    }

    private static boolean validArchive(Path archive, LodPackageOffer offer) throws IOException {
        return Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)
                && Files.size(archive) == offer.bytes()
                && offer.sha256().equals(ContentHash.sha256Hex(archive));
    }

    private static void fetch(LodPackageOffer offer, Path partial) throws IOException, InterruptedException {
        long existing = Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS) ? Files.size(partial) : 0L;
        if (existing < 0L || existing > offer.bytes()) {
            Files.deleteIfExists(partial);
            existing = 0L;
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(offer.url()))
                .timeout(Duration.ofMinutes(15))
                .header("User-Agent", "DynamicStage/1.3")
                .GET();
        if (existing > 0L) {
            request.header("Range", "bytes=" + existing + '-');
        }
        HttpResponse<InputStream> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        boolean append = existing > 0L && status == 206;
        if (!(status == 200 || status == 206)) {
            response.body().close();
            throw new IOException("LOD server returned HTTP " + status);
        }
        if (!append) {
            existing = 0L;
        }
        try (InputStream input = response.body();
             var output = Files.newOutputStream(partial, StandardOpenOption.CREATE,
                     append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[64 * 1024];
            long total = existing;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > offer.bytes()) {
                    throw new IOException("LOD server sent more data than declared");
                }
                output.write(buffer, 0, read);
            }
        }
    }

    private static final class ServerTransfer {
        private final ResourceLocation id;
        private final LodPackageOffer offer;
        private final Path partial;
        private final CompletableFuture<Result> result;
        private long expectedOffset;
        private OutputStream output;

        private ServerTransfer(ResourceLocation id, LodPackageOffer offer, Path partial,
                               CompletableFuture<Result> result, long expectedOffset) {
            this.id = id;
            this.offer = offer;
            this.partial = partial;
            this.result = result;
            this.expectedOffset = expectedOffset;
        }

        private void acceptChunk(LodDownloadChunkPacket packet) {
            if (result.isDone()) {
                return;
            }
            try {
                if (packet.offset() != expectedOffset) {
                    fail("Server LOD transfer offset mismatch");
                    return;
                }
                if (output == null && packet.data().length > 0) {
                    output = Files.newOutputStream(partial, StandardOpenOption.CREATE,
                            expectedOffset == 0L ? StandardOpenOption.TRUNCATE_EXISTING : StandardOpenOption.APPEND);
                }
                byte[] data = packet.data();
                if (output != null && data.length > 0) {
                    output.write(data);
                }
                expectedOffset += data.length;
                if (packet.complete()) {
                    finish();
                }
            } catch (IOException | RuntimeException e) {
                fail(rootMessage(e));
            }
        }

        private void acceptResult(LodDownloadResultPacket packet) {
            if (result.isDone()) {
                return;
            }
            if (!packet.success()) {
                fail(packet.error());
            } else if (expectedOffset != offer.bytes()) {
                fail("Server LOD transfer ended before its declared size");
            } else {
                finish();
            }
        }

        private void finish() {
            try {
                closeOutput();
                if (expectedOffset != offer.bytes()) {
                    fail("Server LOD transfer ended before its declared size");
                    return;
                }
                if (!offer.sha256().equals(ContentHash.sha256Hex(partial))) {
                    Files.deleteIfExists(partial);
                    fail("Downloaded SHA-256 does not match the server manifest");
                    return;
                }
                Path archive = downloadDirectory().resolve(offer.sha256() + ".dstlod");
                Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING);
                result.complete(installArchive(id, offer, archive));
                SERVER_TRANSFERS.remove(id, this);
            } catch (IOException | RuntimeException e) {
                fail(rootMessage(e));
            }
        }

        private void fail(String error) {
            closeOutput();
            SERVER_TRANSFERS.remove(id, this);
            result.complete(offer.required() ? Result.failed(error) : Result.optional(error));
        }

        private void closeOutput() {
            if (output == null) {
                return;
            }
            try {
                output.close();
            } catch (IOException ignored) {
            }
            output = null;
        }
    }

    private static void message(String value) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> {
            Component message = Component.literal(value);
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(message, false);
            } else {
                minecraft.gui.getChat().addMessage(message);
            }
        });
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public record Result(Status status, String error) {
        public boolean proceed() {
            return status != Status.FAILED;
        }

        public static Result ready() {
            return new Result(Status.AVAILABLE, "");
        }

        public static Result optional(String error) {
            return new Result(Status.OPTIONAL_MISSING, error == null ? "" : error);
        }

        public static Result failed(String error) {
            return new Result(Status.FAILED, error == null ? "unknown LOD download error" : error);
        }
    }

    public enum Status { AVAILABLE, OPTIONAL_MISSING, FAILED }
}
