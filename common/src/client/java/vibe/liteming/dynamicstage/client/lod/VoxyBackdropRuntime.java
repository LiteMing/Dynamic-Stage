package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.world.StageWorlds;

import org.jetbrains.annotations.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Soft-dependency integration that lets Voxy own its RocksDB and render lifecycle. */
public final class VoxyBackdropRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyBackdropRuntime.class);
    private static final String VOXY_COMMON = "me.cortex.voxy.commonImpl.VoxyCommon";
    private static final String VOXY_RENDERER = "me.cortex.voxy.client.core.IGetVoxyRenderSystem";

    @Nullable private static volatile Mounted mounted;
    private static boolean stageActivated;
    private static boolean normalInstanceSuspended;
    private static boolean externalInstanceActive;

    private VoxyBackdropRuntime() {
    }

    public static StageBackdropRuntime.Result mount(ClientStageSession.Snapshot snapshot,
                                                     LodPackRegistry.VoxyPack pack) {
        Mounted previous = mounted;
        try {
            verifyVoxyVersion();
            if (previous != null && previous.pack.id().equals(pack.id())) {
                mounted = new Mounted(snapshot.instanceId(), pack);
                return StageBackdropRuntime.Result.success();
            }
            if (!normalInstanceSuspended || externalInstanceActive) {
                shutdownVoxyInstance();
                normalInstanceSuspended = true;
            }
            mounted = new Mounted(snapshot.instanceId(), pack);
            stageActivated = false;
            LOGGER.info("Prepared Voxy LOD pack {} from {}", pack.id(), pack.baseDirectory());
            return StageBackdropRuntime.Result.success();
        } catch (Throwable e) {
            mounted = previous;
            LOGGER.warn("Could not mount Voxy stage backdrop: {}", e.toString());
            return StageBackdropRuntime.Result.failure(rootMessage(e));
        }
    }

    public static void unmount() {
        Mounted previous = mounted;
        mounted = null;
        stageActivated = false;
        if (previous == null || !normalInstanceSuspended) {
            return;
        }
        try {
            restoreNormalInstance(!StageWorlds.isStageLevel(Minecraft.getInstance().level));
        } catch (Throwable e) {
            LOGGER.warn("Could not restore Voxy's normal storage instance: {}", e.toString());
        }
    }

    public static boolean isMounted(UUID instanceId) {
        Mounted current = mounted;
        return current != null && current.instanceId.equals(instanceId);
    }

    public static List<CurrentSource> currentSources() {
        try {
            Class<?> common = Class.forName(VOXY_COMMON);
            Object instance = common.getMethod("getInstance").invoke(null);
            if (instance == null) {
                return List.of();
            }
            Field activeWorldsField = findField(instance.getClass(), "activeWorlds");
            Object activeWorlds = activeWorldsField.get(instance);
            if (!(activeWorlds instanceof Map<?, ?> worlds) || worlds.isEmpty()) {
                return List.of();
            }
            Map<Path, CurrentSource> sources = new LinkedHashMap<>();
            for (var entry : worlds.entrySet()) {
                Object identifier = entry.getKey();
                Object world = entry.getValue();
                if (identifier == null || world == null) {
                    continue;
                }
                String worldId = String.valueOf(identifier.getClass().getMethod("getWorldId").invoke(identifier));
                Field storageField = findField(world.getClass(), "storage");
                Field backendField = findField(storageField.get(world).getClass(), "backend");
                Object backend = backendField.get(storageField.get(world));
                Path storage = findRocksDbPath(backend);
                sources.putIfAbsent(storage, new CurrentSource(storage, worldId));
            }
            return new ArrayList<>(sources.values());
        } catch (ClassNotFoundException e) {
            return List.of();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not inspect Voxy's current LOD storage", e);
        }
    }

    public static boolean needsStageActivation(UUID instanceId) {
        return isMounted(instanceId) && !stageActivated;
    }

    public static StageBackdropRuntime.Result activateStage(UUID instanceId) {
        if (!isMounted(instanceId)) {
            return StageBackdropRuntime.Result.failure("The prepared Voxy LOD package is no longer active");
        }
        if (stageActivated) {
            return StageBackdropRuntime.Result.success();
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !StageWorlds.isStageLevel(minecraft.level)) {
            return StageBackdropRuntime.Result.failure("The client has not entered the Dynamic Stage level");
        }
        try {
            if (!externalInstanceActive) {
                createExternalInstance();
            }
            ensureRenderer();
            verifyOpenedStorage();
            stageActivated = true;
            Mounted current = mounted;
            LOGGER.info("Activated Voxy LOD pack {} for stage instance {}",
                    current == null ? "<unmounted>" : current.pack.id(), instanceId);
            return StageBackdropRuntime.Result.success();
        } catch (Throwable e) {
            LOGGER.debug("Voxy stage backdrop is not ready yet: {}", rootMessage(e), e);
            return StageBackdropRuntime.Result.failure(rootMessage(e));
        }
    }

    @Nullable
    public static Path selectedBasePath() {
        Mounted current = mounted;
        return current == null ? null : current.pack.baseDirectory();
    }

    @Nullable
    public static String selectedWorldId() {
        Mounted current = mounted;
        return current == null ? null : current.pack.worldId();
    }

    public static boolean shouldOverrideCurrentStage() {
        return mounted != null && externalInstanceActive;
    }

    public static void leaveStageLevel() {
        if (mounted == null || !normalInstanceSuspended) {
            return;
        }
        mounted = null;
        stageActivated = false;
        try {
            restoreNormalInstance(false);
        } catch (Throwable e) {
            LOGGER.warn("Could not release Voxy's stage storage during level change: {}", e.toString());
        }
    }

    private static void shutdownVoxyInstance() throws ReflectiveOperationException {
        Class<?> common = Class.forName(VOXY_COMMON);
        Object renderer = Minecraft.getInstance().levelRenderer;
        Class<?> rendererInterface = Class.forName(VOXY_RENDERER);
        Method shutdownRenderer = rendererInterface.getMethod("voxy$shutdownRenderer");
        shutdownRenderer.invoke(renderer);
        common.getMethod("shutdownInstance").invoke(null);
        externalInstanceActive = false;
    }

    private static void createExternalInstance() throws ReflectiveOperationException {
        shutdownVoxyInstance();
        Class<?> common = Class.forName(VOXY_COMMON);
        // Voxy asks getBasePath() from inside its constructor, so the redirect
        // must already be active before createInstance() invokes the factory.
        externalInstanceActive = true;
        try {
            common.getMethod("createInstance").invoke(null);
            if (common.getMethod("getInstance").invoke(null) == null) {
                throw new IllegalStateException("Voxy did not create a client instance");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            externalInstanceActive = false;
            try {
                common.getMethod("shutdownInstance").invoke(null);
            } catch (ReflectiveOperationException | RuntimeException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private static void restoreNormalInstance(boolean createRenderer) throws ReflectiveOperationException {
        shutdownVoxyInstance();
        Class<?> common = Class.forName(VOXY_COMMON);
        common.getMethod("createInstance").invoke(null);
        if (common.getMethod("getInstance").invoke(null) == null) {
            throw new IllegalStateException("Voxy did not restore its normal client instance");
        }
        normalInstanceSuspended = false;
        if (createRenderer && Minecraft.getInstance().level != null) {
            ensureRenderer();
        }
    }

    private static void ensureRenderer() throws ReflectiveOperationException {
        Object renderer = Minecraft.getInstance().levelRenderer;
        Class<?> rendererInterface = Class.forName(VOXY_RENDERER);
        if (rendererInterface.getMethod("voxy$getRenderSystem").invoke(renderer) == null) {
            rendererInterface.getMethod("voxy$createRenderer").invoke(renderer);
        }
        if (rendererInterface.getMethod("voxy$getRenderSystem").invoke(renderer) == null) {
            throw new IllegalStateException("Voxy did not create a renderer for the current level");
        }
    }

    private static void verifyOpenedStorage() throws ReflectiveOperationException {
        Mounted current = mounted;
        if (current == null) {
            throw new IllegalStateException("Voxy package was unmounted during activation");
        }
        Object instance = Class.forName(VOXY_COMMON).getMethod("getInstance").invoke(null);
        Object base = instance.getClass().getMethod("getStorageBasePath").invoke(instance);
        if (!(base instanceof Path actualBase)) {
            throw new IllegalStateException("Voxy returned an unsupported storage base path");
        }
        samePath(actualBase, current.pack.baseDirectory(), "Voxy opened a different storage base");

        Field activeWorldsField = findField(instance.getClass().getSuperclass(), "activeWorlds");
        Object activeWorlds = activeWorldsField.get(instance);
        if (!(activeWorlds instanceof java.util.Map<?, ?> map) || map.isEmpty()) {
            throw new IllegalStateException("Voxy has not opened the stage world storage yet");
        }
        Object world = null;
        for (var entry : map.entrySet()) {
            Object identifier = entry.getKey();
            if (current.pack.worldId().equals(identifier.getClass().getMethod("getWorldId").invoke(identifier))) {
                world = entry.getValue();
                break;
            }
        }
        if (world == null) {
            throw new IllegalStateException("Voxy opened a different world identifier");
        }
        Field storageField = findField(world.getClass(), "storage");
        Field backendField = findField(storageField.get(world).getClass(), "backend");
        Object backend = backendField.get(storageField.get(world));
        Path actualStorage = findRocksDbPath(backend);
        samePath(actualStorage, current.pack.storageDirectory(), "Voxy opened a different RocksDB database");
        LOGGER.info("Voxy opened stage LOD storage {}", current.pack.storageDirectory());
    }

    private static Path findRocksDbPath(Object backend) throws ReflectiveOperationException {
        Object backends = backend.getClass().getMethod("collectAllBackends").invoke(backend);
        if (backends instanceof Iterable<?> iterable) {
            for (Object candidate : iterable) {
                if (candidate != null && candidate.getClass().getName().endsWith("RocksDBStorageBackend")) {
                    Object db = findField(candidate.getClass(), "db").get(candidate);
                    Object name = db.getClass().getMethod("getName").invoke(db);
                    return Path.of(String.valueOf(name)).toAbsolutePath().normalize();
                }
            }
        }
        throw new IllegalStateException("Voxy storage config does not contain RocksDB");
    }

    private static void verifyVoxyVersion() throws ReflectiveOperationException {
        String version = String.valueOf(Class.forName(VOXY_COMMON).getField("MOD_VERSION").get(null));
        if (!version.startsWith(LodPackRegistry.VOXY_VERSION)) {
            throw new IllegalStateException("LOD pack requires Voxy " + LodPackRegistry.VOXY_VERSION
                    + ".x, found " + version);
        }
    }

    private static void samePath(Path actual, Path expected, String message) {
        try {
            if (!Files.isSameFile(actual.toAbsolutePath().normalize(), expected.toAbsolutePath().normalize())) {
                throw new IllegalStateException(message + ": " + actual);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(message + ": " + actual, e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(type.getName() + '.' + name);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record Mounted(UUID instanceId, LodPackRegistry.VoxyPack pack) {
    }

    public record CurrentSource(Path storage, String worldId) {
    }
}
