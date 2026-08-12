package vibe.liteming.dynamicstage.stage;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.backdrop.BackdropProducts;
import vibe.liteming.dynamicstage.bake.StageBackdropBaker;
import vibe.liteming.dynamicstage.network.DynamicStageNetwork;
import vibe.liteming.dynamicstage.network.BackdropDistribution;
import vibe.liteming.dynamicstage.world.StageWorlds;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Server authority for preparing, entering, restoring, and leaving stage sessions. */
public final class StageSessionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(StageSessionManager.class);
    private static final AtomicLong REQUEST_SEQUENCE = new AtomicLong();
    private static final ConcurrentHashMap<UUID, Long> PREPARATIONS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, CompletableFuture<BackdropProducts.Product>> PRODUCTS =
            new ConcurrentHashMap<>();
    private static final ExecutorService BAKE_WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "DynamicStage-BackdropBaker");
        thread.setDaemon(true);
        return thread;
    });

    private StageSessionManager() {
    }

    public static boolean prepareAndEnter(ServerPlayer player, String stageId, BlockPos anchor,
                                          Path dataFile, String source) {
        MinecraftServer server = player.getServer();
        if (server == null || stageId.isBlank() || stageId.length() > 128) {
            return false;
        }
        StageSessionData sessionData = StageSessionData.get(server);
        if (sessionData.get(player.getUUID()).isPresent() || PREPARATIONS.containsKey(player.getUUID())) {
            player.sendSystemMessage(Component.literal("A Dynamic Stage session is already active or preparing."));
            return false;
        }

        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        String sourceDimension = player.serverLevel().dimension().location().toString();
        ResourceKey<Level> expectedDimension = player.serverLevel().dimension();
        long request = REQUEST_SEQUENCE.incrementAndGet();
        PREPARATIONS.put(player.getUUID(), request);
        UUID playerId = player.getUUID();
        ReturnPoint returnPoint = new ReturnPoint(player.serverLevel().dimension(), player.position(),
                player.getYRot(), player.getXRot());
        player.sendSystemMessage(Component.literal("Preparing LOD backdrop for stage '" + stageId + "'..."));
        String productKey = worldRoot.toAbsolutePath().normalize() + "|" + stageId + '|' + sourceDimension + '|'
                + source + '|'
                + dataFile.toAbsolutePath().normalize() + '|' + anchor.asLong();
        CompletableFuture<BackdropProducts.Product> productFuture = PRODUCTS.computeIfAbsent(productKey, ignored ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        String requestKey = StageBackdropBaker.requestKey(dataFile, anchor, source, sourceDimension);
                        BackdropProducts.Product cached = BackdropProducts.findByRequest(worldRoot, stageId, requestKey);
                        return cached != null ? cached : StageBackdropBaker.bake(worldRoot, stageId, sourceDimension,
                                dataFile, anchor, source, requestKey);
                    } catch (Exception e) {
                        throw new java.util.concurrent.CompletionException(e);
                    }
                }, BAKE_WORKER));
        productFuture.whenComplete((product, error) -> PRODUCTS.remove(productKey, productFuture));
        productFuture.whenComplete((product, error) -> server.execute(() -> {
            if (!PREPARATIONS.remove(playerId, request)) {
                return;
            }
            ServerPlayer current = server.getPlayerList().getPlayer(playerId);
            if (current == null) {
                return;
            }
            if (error != null) {
                LOGGER.warn("Backdrop preparation failed for {}: {}", playerId, error.toString());
                current.sendSystemMessage(Component.literal("Failed to prepare the LOD backdrop: "
                        + rootMessage(error)));
                return;
            }
            if (!current.serverLevel().dimension().equals(expectedDimension)) {
                current.sendSystemMessage(Component.literal("Stage entry cancelled because the source dimension changed."));
                return;
            }
            enter(current, stageId, anchor, source, product, returnPoint);
        }));
        return true;
    }

    public static boolean exit(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        boolean cancelledPreparation = PREPARATIONS.remove(player.getUUID()) != null;
        Optional<StageSession> removed = StageSessionData.get(server).remove(player.getUUID());
        DynamicStageNetwork.clearSession(player);
        if (removed.isEmpty()) {
            return cancelledPreparation;
        }
        StageSession session = removed.get();
        ServerLevel returnLevel = server.getLevel(session.returnDimension());
        if (returnLevel == null) {
            returnLevel = server.overworld();
        }
        player.stopRiding();
        player.fallDistance = 0.0F;
        player.teleportTo(returnLevel,
                session.returnPosition().x, session.returnPosition().y, session.returnPosition().z,
                session.returnYRot(), session.returnXRot());
        return true;
    }

    public static void releaseWithoutTeleport(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server != null) {
            StageSessionData.get(server).remove(player.getUUID());
        }
        PREPARATIONS.remove(player.getUUID());
        DynamicStageNetwork.clearSession(player);
    }

    /** Cancels only an in-flight preparation; active persisted sessions survive logout. */
    public static void onLogout(ServerPlayer player) {
        PREPARATIONS.remove(player.getUUID());
        BackdropDistribution.clearPlayer(player.getUUID());
    }

    public static void onServerStopped() {
        PREPARATIONS.clear();
        PRODUCTS.values().forEach(future -> future.cancel(true));
        PRODUCTS.clear();
        BackdropDistribution.clearAll();
    }

    public static void restore(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Optional<StageSession> found = StageSessionData.get(server).get(player.getUUID());
        if (found.isEmpty()) {
            DynamicStageNetwork.clearSession(player);
            if (StageWorlds.isStageLevel(player.level())) {
                BlockPos spawn = server.overworld().getSharedSpawnPos();
                player.teleportTo(server.overworld(), spawn.getX() + 0.5D, spawn.getY(), spawn.getZ() + 0.5D,
                        player.getYRot(), player.getXRot());
            }
            return;
        }
        StageSession session = found.get();
        if (!StageWorlds.isStageLevel(player.level())) {
            StageSessionData.get(server).remove(player.getUUID());
            DynamicStageNetwork.clearSession(player);
            return;
        }
        if (BackdropProducts.resolve(server, session.stageId(), session.backdropHash()) == null) {
            player.sendSystemMessage(Component.literal("The saved stage backdrop is unavailable; returning safely."));
            exit(player);
            return;
        }
        DynamicStageNetwork.sendSession(player, session);
    }

    public static Optional<StageSession> get(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server == null ? Optional.empty() : StageSessionData.get(server).get(player.getUUID());
    }

    private static boolean enter(ServerPlayer player, String stageId, BlockPos anchor, String source,
                                 BackdropProducts.Product product, ReturnPoint returnPoint) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }
        ServerLevel stageLevel = server.getLevel(StageWorlds.STG_STAGE);
        if (stageLevel == null) {
            player.sendSystemMessage(Component.literal("The Dynamic Stage dimension is unavailable."));
            return false;
        }
        StageSessionData data = StageSessionData.get(server);
        if (data.get(player.getUUID()).isPresent()) {
            return false;
        }
        int slot = StagePlacement.allocate(data);
        if (slot < 0) {
            player.sendSystemMessage(Component.literal("All isolated Dynamic Stage regions are in use."));
            return false;
        }
        StageSession session = new StageSession(
                player.getUUID(), stageId, source, anchor, slot,
                returnPoint.dimension(), returnPoint.position(), returnPoint.yRot(), returnPoint.xRot(),
                product.hash(), product.bytes());
        data.put(session);
        BlockPos origin = session.stageOrigin();
        player.stopRiding();
        player.fallDistance = 0.0F;
        player.teleportTo(stageLevel, origin.getX() + 0.5D, origin.getY(), origin.getZ() + 0.5D,
                player.getYRot(), player.getXRot());
        DynamicStageNetwork.sendSession(player, session);
        player.sendSystemMessage(Component.literal("Entered stage '" + stageId + "' in isolated region " + slot + '.'));
        return true;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record ReturnPoint(ResourceKey<Level> dimension, Vec3 position, float yRot, float xRot) {
    }
}
