package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.client.config.StageClientConfig;
import vibe.liteming.dynamicstage.lod.LodPackageOffer;
import vibe.liteming.dynamicstage.util.ContentHash;

import java.io.IOException;
import java.io.InputStream;
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
        CompletableFuture<Result> created = CompletableFuture.supplyAsync(() -> download(id, offer), EXECUTOR);
        existing = ACTIVE.putIfAbsent(id, created);
        if (existing != null) {
            return existing;
        }
        created.whenComplete((result, error) -> ACTIVE.remove(id, created));
        return created;
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
            Path downloadDirectory = Minecraft.getInstance().gameDirectory.toPath().resolve("dynamicstage")
                    .resolve("downloads").toAbsolutePath().normalize();
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
            long extractionLimit = Math.min(2L * 1024L * 1024L * 1024L,
                    Math.max(64L * 1024L * 1024L, offer.bytes() * 8L));
            LodPackArchive.install(archive, LodPackRegistry.rootDirectory(), id, extractionLimit);
            LodPackRegistry.load(id);
            message("Installed stage LOD package '" + id + "'.");
            return Result.ready();
        } catch (Exception e) {
            String error = rootMessage(e);
            return offer.required() ? Result.failed(error) : Result.optional(error);
        }
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
