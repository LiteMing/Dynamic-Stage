package vibe.liteming.dynamicstage.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import vibe.liteming.dynamicstage.client.lod.CurrentLodCache;
import vibe.liteming.dynamicstage.client.lod.LodPackArchive;
import vibe.liteming.dynamicstage.client.lod.LodPackImporter;
import vibe.liteming.dynamicstage.client.lod.LodPackRegistry;
import vibe.liteming.dynamicstage.client.config.StageClientConfig;
import vibe.liteming.dynamicstage.world.StageWorlds;
import vibe.liteming.dynamicstage.client.editor.StageTemplateEditorScreen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local filesystem commands. These never accept import requests from a server packet. */
public final class StageLodClientCommands {
    private static final Set<ResourceLocation> ACTIVE_IMPORTS = ConcurrentHashMap.newKeySet();
    private static final ExecutorService IMPORT_EXECUTOR = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Dynamic Stage LOD Importer");
        thread.setDaemon(true);
        return thread;
    });

    private StageLodClientCommands() {
    }

    public static <S> void register(CommandDispatcher<S> dispatcher) {
        dispatcher.register(buildRoot("dstage"));
        dispatcher.register(buildRoot("dstageclient"));
    }

    private static <S> LiteralArgumentBuilder<S> buildRoot(String name) {
        LiteralArgumentBuilder<S> root = LiteralArgumentBuilder.literal(name);
        root.then(LiteralArgumentBuilder.<S>literal("start")
                .then(RequiredArgumentBuilder.<S, String>argument("stage", StringArgumentType.string())
                        .executes(StageLodClientCommands::startAutomatic)));
        root.then(LiteralArgumentBuilder.<S>literal("editor").executes(context -> openEditor()));
        LiteralArgumentBuilder<S> lod = LiteralArgumentBuilder.literal("lod");
        lod.then(LiteralArgumentBuilder.<S>literal("root")
                .executes(context -> showRoot()));

        lod.then(LiteralArgumentBuilder.<S>literal("import")
                .then(importMode("link", LodPackImporter.Mode.LINK))
                .then(importMode("link-relative", LodPackImporter.Mode.LINK_RELATIVE))
                .then(importMode("copy", LodPackImporter.Mode.COPY)));
        lod.then(LiteralArgumentBuilder.<S>literal("export")
                .then(RequiredArgumentBuilder.<S, String>argument("pack", StringArgumentType.word())
                        .then(RequiredArgumentBuilder.<S, String>argument("output", StringArgumentType.greedyString())
                                .suggests(StageLodClientCommands::suggestArchivePath)
                                .executes(StageLodClientCommands::exportPack))));
        LiteralArgumentBuilder<S> downloads = LiteralArgumentBuilder.literal("downloads");
        downloads.then(LiteralArgumentBuilder.<S>literal("status")
                .executes(context -> showDownloadPolicy()));
        downloads.then(LiteralArgumentBuilder.<S>literal("on")
                .executes(context -> setDownloadPolicy(true)));
        downloads.then(LiteralArgumentBuilder.<S>literal("off")
                .executes(context -> setDownloadPolicy(false)));
        downloads.then(LiteralArgumentBuilder.<S>literal("max")
                .then(RequiredArgumentBuilder.<S, Integer>argument("mib", IntegerArgumentType.integer(1, 512))
                        .executes(StageLodClientCommands::setDownloadLimit)));
        lod.then(downloads);
        root.then(lod);
        return root;
    }

    private static <S> LiteralArgumentBuilder<S> importMode(String name, LodPackImporter.Mode mode) {
        RequiredArgumentBuilder<S, String> source = RequiredArgumentBuilder
                .<S, String>argument("source_path", StringArgumentType.greedyString())
                .suggests(StageLodClientCommands::suggestPaths)
                .executes(context -> startImport(context, mode));
        RequiredArgumentBuilder<S, String> pack = RequiredArgumentBuilder
                .<S, String>argument("pack", StringArgumentType.word())
                .then(source);
        return LiteralArgumentBuilder.<S>literal(name).then(pack);
    }

    private static <S> int startAutomatic(CommandContext<S> context) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (minecraft.player == null || minecraft.level == null || connection == null) {
            message(Component.literal("Cannot start a stage without an active client level."));
            return 0;
        }
        if (StageWorlds.isStageLevel(minecraft.level)) {
            message(Component.literal("Exit the active stage before starting another one."));
            return 0;
        }
        String stage = StringArgumentType.getString(context, "stage");
        BlockPos anchor = minecraft.player.blockPosition();
        ResourceKey<Level> dimension = minecraft.level.dimension();

        CurrentLodCache cache;
        try {
            cache = CurrentLodCache.discover();
        } catch (Exception e) {
            message(Component.literal("Could not inspect the current native LOD cache: " + rootMessage(e)));
            return 0;
        }
        if (cache == null) {
            ResourceLocation missing = CurrentLodCache.missingPackId(stage, dimension.location());
            message(Component.literal("No native LOD cache is open for the current level; entering without one."));
            sendStart(connection, stage, missing, anchor);
            return 1;
        }

        Path gameDirectory = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        ResourceLocation packId = cache.automaticPackId(gameDirectory);
        if (!ACTIVE_IMPORTS.add(packId)) {
            message(Component.literal("Automatic LOD package preparation is already running: " + packId));
            return 0;
        }
        Path packageRoot = LodPackRegistry.rootDirectory();
        message(Component.literal("Preparing the current " + cache.backend().displayName()
                + " LOD cache for stage '" + stage + "'."));
        CompletableFuture.runAsync(() -> {
            try {
                prepareAutomaticPack(packageRoot, gameDirectory, packId, cache);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, IMPORT_EXECUTOR).whenComplete((ignored, error) -> {
            ACTIVE_IMPORTS.remove(packId);
            minecraft.execute(() -> {
                if (minecraft.getConnection() != connection || minecraft.level == null
                        || !minecraft.level.dimension().equals(dimension)) {
                    message(Component.literal("Automatic stage start cancelled because the client level changed."));
                    return;
                }
                if (error != null) {
                    if (missingContent(error)) {
                        message(Component.literal("The current LOD cache disappeared; entering without it."));
                        sendStart(connection, stage, packId, anchor);
                        return;
                    }
                    message(Component.literal("Could not prepare the current LOD cache: " + rootMessage(error)));
                    return;
                }
                sendStart(connection, stage, packId, anchor);
            });
        });
        return 1;
    }

    private static int openEditor() {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> minecraft.setScreen(new StageTemplateEditorScreen()));
        return 1;
    }

    public static CompletableFuture<ResourceLocation> prepareCurrentLod() {
        final CurrentLodCache cache;
        try {
            cache = CurrentLodCache.discover();
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        if (cache == null) {
            return CompletableFuture.completedFuture(null);
        }
        Minecraft minecraft = Minecraft.getInstance();
        Path gameDirectory = minecraft.gameDirectory.toPath().toAbsolutePath().normalize();
        ResourceLocation packId = cache.automaticPackId(gameDirectory);
        if (!ACTIVE_IMPORTS.add(packId)) {
            return CompletableFuture.failedFuture(new IOException(
                    "Automatic LOD package preparation is already running: " + packId));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                prepareAutomaticPack(LodPackRegistry.rootDirectory(), gameDirectory, packId, cache);
                return packId;
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }, IMPORT_EXECUTOR).whenComplete((ignored, error) -> ACTIVE_IMPORTS.remove(packId));
    }

    private static void prepareAutomaticPack(Path packageRoot, Path gameDirectory, ResourceLocation packId,
                                             CurrentLodCache cache) throws IOException {
        try {
            LodPackRegistry.Pack existing = LodPackRegistry.load(packageRoot, packId);
            if (cache.matches(existing)) {
                return;
            }
            throw new IOException("automatic package ID collision: " + packId);
        } catch (LodPackRegistry.UnavailableException unavailable) {
            Path destination = packageRoot.resolve(packId.getNamespace()).resolve(packId.getPath()).normalize();
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("automatic package is incomplete; remove it before retrying: " + destination,
                        unavailable);
            }
        }
        LodPackImporter.Mode mode = cache.source().startsWith(gameDirectory)
                ? LodPackImporter.Mode.LINK_RELATIVE : LodPackImporter.Mode.LINK;
        LodPackImporter.importPack(packageRoot, packId, cache.source(), mode);
    }

    private static void sendStart(ClientPacketListener connection, String stage,
                                  ResourceLocation packId, BlockPos anchor) {
        String command = "dstage start " + StringArgumentType.escapeIfRequired(stage) + ' ' + packId
                + ' ' + anchor.getX() + ' ' + anchor.getY() + ' ' + anchor.getZ();
        connection.sendCommand(command);
    }

    private static int showRoot() {
        message(Component.literal("Dynamic Stage LOD packages: " + LodPackRegistry.rootDirectory()));
        return 1;
    }

    private static <S> int startImport(CommandContext<S> context, LodPackImporter.Mode mode) {
        String rawId = StringArgumentType.getString(context, "pack");
        ResourceLocation id = ResourceLocation.tryParse(rawId);
        if (id == null) {
            message(Component.literal("Invalid LOD package ID: " + rawId));
            return 0;
        }

        Path source;
        try {
            source = resolvePath(StringArgumentType.getString(context, "source_path"));
        } catch (IllegalArgumentException e) {
            message(Component.literal("Invalid LOD source path: " + e.getMessage()));
            return 0;
        }
        if (mode == LodPackImporter.Mode.LINK_RELATIVE) {
            Path gameDirectory = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
            if (!source.startsWith(gameDirectory)) {
                message(Component.literal("A relative LOD link must point inside the current game instance: "
                        + gameDirectory));
                return 0;
            }
        }
        if (!ACTIVE_IMPORTS.add(id)) {
            message(Component.literal("LOD package import is already running: " + id));
            return 0;
        }

        Path packageRoot = LodPackRegistry.rootDirectory();
        String action = switch (mode) {
            case LINK -> "Linking";
            case LINK_RELATIVE -> "Creating portable relative link to";
            case COPY -> "Copying";
        };
        message(Component.literal(action
                + " native LOD cache from " + source + ". Keep the source game instance closed."));
        CompletableFuture.supplyAsync(() -> {
            try {
                return LodPackImporter.importPack(packageRoot, id, source, mode);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }, IMPORT_EXECUTOR).whenComplete((result, error) -> {
            ACTIVE_IMPORTS.remove(id);
            Minecraft.getInstance().execute(() -> {
                if (error != null) {
                    message(Component.literal("Could not import LOD package " + id + ": " + rootMessage(error)));
                    return;
                }
                if (result.mode() != LodPackImporter.Mode.COPY) {
                    String linked = result.mode() == LodPackImporter.Mode.LINK_RELATIVE
                            ? "Relatively linked " : "Linked ";
                    message(Component.literal(linked + result.backend().displayName() + " LOD package "
                            + id + " to " + result.source() + '.'));
                } else {
                    double mebibytes = result.byteCount() / (1024.0D * 1024.0D);
                    message(Component.literal("Imported " + result.backend().displayName() + " LOD package "
                            + id + " (" + result.fileCount() + " files, "
                            + String.format(Locale.ROOT, "%.1f", mebibytes) + " MiB)."));
                }
            });
        });
        return 1;
    }

    private static <S> int exportPack(CommandContext<S> context) {
        ResourceLocation id = ResourceLocation.tryParse(StringArgumentType.getString(context, "pack"));
        if (id == null) {
            message(Component.literal("Invalid LOD package ID."));
            return 0;
        }
        Path output;
        try {
            output = resolvePath(StringArgumentType.getString(context, "output"));
        } catch (RuntimeException e) {
            message(Component.literal("Invalid LOD archive path: " + e.getMessage()));
            return 0;
        }
        try {
            LodPackRegistry.Pack pack = LodPackRegistry.load(id);
            message(Component.literal("Exporting LOD package '" + id + "' to " + output + '.'));
            CompletableFuture<LodPackArchive.ArchiveInfo> export = CompletableFuture.supplyAsync(() -> {
                try {
                    return LodPackArchive.create(pack.directory(), output);
                } catch (IOException e) {
                    throw new java.util.concurrent.CompletionException(e);
                }
            }, IMPORT_EXECUTOR);
            export.whenComplete((result, error) -> Minecraft.getInstance().execute(() -> {
                if (error != null) {
                    message(Component.literal("Could not export LOD package '" + id + "': "
                            + rootMessage(error)));
                    return;
                }
                message(Component.literal("Exported " + result.files() + " files ("
                        + String.format(Locale.ROOT, "%.1f", result.bytes() / 1048576.0D) + " MiB, SHA-256 "
                        + result.sha256() + ") to " + result.path() + '.'));
            }));
            return 1;
        } catch (Exception e) {
            message(Component.literal("Could not open LOD package '" + id + "': " + rootMessage(e)));
            return 0;
        }
    }

    private static int showDownloadPolicy() {
        message(Component.literal("Server LOD downloads: "
                + (StageClientConfig.allowServerLodDownloads() ? "on" : "off")
                + ", maximum " + StageClientConfig.maxServerLodDownloadMib() + " MiB."));
        return 1;
    }

    private static int setDownloadPolicy(boolean allow) {
        try {
            StageClientConfig.saveServerLodDownloads(allow, StageClientConfig.maxServerLodDownloadMib());
            message(Component.literal("Server LOD downloads " + (allow ? "enabled" : "disabled") + '.'));
            return 1;
        } catch (IOException e) {
            message(Component.literal("Could not save client config: " + rootMessage(e)));
            return 0;
        }
    }

    private static <S> int setDownloadLimit(CommandContext<S> context) {
        int mib = IntegerArgumentType.getInteger(context, "mib");
        try {
            StageClientConfig.saveServerLodDownloads(StageClientConfig.allowServerLodDownloads(), mib);
            message(Component.literal("Server LOD download maximum set to " + mib + " MiB."));
            return 1;
        } catch (IOException | IllegalArgumentException e) {
            message(Component.literal("Could not save client config: " + rootMessage(e)));
            return 0;
        }
    }

    private static Path resolvePath(String value) {
        String raw = value.trim();
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            raw = raw.substring(1, raw.length() - 1);
        }
        if (raw.equals("~") || raw.startsWith("~/") || raw.startsWith("~\\")) {
            raw = System.getProperty("user.home") + raw.substring(1);
        }
        Path path = Path.of(raw);
        if (!path.isAbsolute()) {
            path = Minecraft.getInstance().gameDirectory.toPath().resolve(path);
        }
        return path.toAbsolutePath().normalize();
    }

    private static <S> CompletableFuture<Suggestions> suggestPaths(CommandContext<S> context,
                                                                    SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        try {
            Path gameDirectory = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
            Path entered = remaining.isBlank() ? gameDirectory : resolvePath(remaining);
            Path directory;
            String prefix;
            if (Files.isDirectory(entered)) {
                directory = entered;
                prefix = "";
            } else {
                directory = entered.getParent();
                prefix = entered.getFileName() == null ? "" : entered.getFileName().toString();
            }
            if (directory == null || !Files.isDirectory(directory)) {
                return builder.buildFuture();
            }
            try (var paths = Files.list(directory)) {
                paths.filter(path -> Files.isDirectory(path) || relevantFile(path))
                        .filter(path -> prefix.isEmpty()
                                || path.getFileName().toString().regionMatches(true, 0, prefix, 0, prefix.length()))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .limit(80)
                        .forEach(path -> builder.suggest(path.toAbsolutePath().normalize().toString()
                                + (Files.isDirectory(path) ? java.io.File.separator : "")));
            }
        } catch (Exception ignored) {
        }
        return builder.buildFuture();
    }

    private static <S> CompletableFuture<Suggestions> suggestArchivePath(CommandContext<S> context,
                                                                           SuggestionsBuilder builder) {
        String remaining = builder.getRemaining();
        Path base = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize()
                .resolve("dynamicstage");
        try {
            Path entered = remaining.isBlank() ? base : resolvePath(remaining);
            Path directory = Files.isDirectory(entered) ? entered : entered.getParent();
            String prefix = Files.isDirectory(entered) ? "" : entered.getFileName() == null
                    ? "" : entered.getFileName().toString();
            if (directory == null || !Files.isDirectory(directory)) {
                return builder.buildFuture();
            }
            try (var paths = Files.list(directory)) {
                paths.filter(path -> Files.isDirectory(path) || path.getFileName().toString().endsWith(".dstlod"))
                        .filter(path -> prefix.isEmpty() || path.getFileName().toString()
                                .regionMatches(true, 0, prefix, 0, prefix.length()))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                        .limit(80)
                        .forEach(path -> builder.suggest(path.toAbsolutePath().normalize().toString()
                                + (Files.isDirectory(path) ? java.io.File.separator : "")));
            }
        } catch (Exception ignored) {
        }
        return builder.buildFuture();
    }

    private static boolean relevantFile(Path path) {
        String name = path.getFileName().toString();
        return "DistantHorizons.sqlite".equals(name) || "CURRENT".equals(name);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private static boolean missingContent(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof LodPackRegistry.UnavailableException
                    || current instanceof java.nio.file.NoSuchFileException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static void message(Component component) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(component, false);
        } else {
            minecraft.gui.getChat().addMessage(component);
        }
    }
}
