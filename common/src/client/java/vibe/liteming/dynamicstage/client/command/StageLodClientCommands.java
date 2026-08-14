package vibe.liteming.dynamicstage.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import vibe.liteming.dynamicstage.client.lod.LodPackImporter;
import vibe.liteming.dynamicstage.client.lod.LodPackRegistry;

import java.nio.file.Files;
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
        LiteralArgumentBuilder<S> lod = LiteralArgumentBuilder.literal("lod");
        lod.then(LiteralArgumentBuilder.<S>literal("root")
                .executes(context -> showRoot()));

        lod.then(LiteralArgumentBuilder.<S>literal("import")
                .then(importMode("link", LodPackImporter.Mode.LINK))
                .then(importMode("link-relative", LodPackImporter.Mode.LINK_RELATIVE))
                .then(importMode("copy", LodPackImporter.Mode.COPY)));
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

    private static void message(Component component) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(component, false);
        } else {
            minecraft.gui.getChat().addMessage(component);
        }
    }
}
