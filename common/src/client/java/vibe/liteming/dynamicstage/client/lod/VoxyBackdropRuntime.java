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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Soft-dependency integration that lets Voxy own its RocksDB and render lifecycle. */
public final class VoxyBackdropRuntime {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyBackdropRuntime.class);
    private static final String VOXY_COMMON = "me.cortex.voxy.commonImpl.VoxyCommon";
    private static final String VOXY_RENDERER = "me.cortex.voxy.client.core.IGetVoxyRenderSystem";
    private static final String VOXY_STAGE_COMPAT = "me.cortex.voxy.client.DynamicStageCompat";

    @Nullable private static volatile Mounted mounted;
    @Nullable private static Method preserveCameraSectionMethod;
    @Nullable private static Boolean lastPreserveCameraSection;
    @Nullable private static volatile Object stageWorldEngine;
    @Nullable private static Method acquireSectionMethod;
    @Nullable private static Method sectionDataMethod;
    @Nullable private static Method releaseSectionMethod;
    private static boolean stageActivated;
    private static boolean normalInstanceSuspended;
    private static boolean externalInstanceActive;
    private static boolean stageCompatResolved;
    private static boolean stageCompatWarningLogged;

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
        stageWorldEngine = null;
        setPreserveCameraSection(false);
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
                Object storageObject = storageField.get(world);
                Field backendField = findField(storageObject.getClass(), "backend");
                Object backend = backendField.get(storageObject);
                Path storage = findRocksDbPath(backend);
                boolean ingestEnabled = (boolean) instance.getClass()
                        .getMethod("isIngestEnabled", Class.forName(
                                "me.cortex.voxy.commonImpl.WorldIdentifier"))
                        .invoke(instance, identifier);
                sources.putIfAbsent(storage, new CurrentSource(storage, worldId,
                        hasStoredSections(backend), ingestEnabled));
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

    @Nullable
    public static BlockLookup openBlockLookup() {
        Object world = stageWorldEngine;
        if (!stageActivated || world == null || !shouldOverrideCurrentStage()) {
            return null;
        }
        try {
            resolveBlockLookup(world);
            return new BlockLookup(world, acquireSectionMethod, sectionDataMethod, releaseSectionMethod);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not query Voxy LOD blocks", e);
        }
    }

    public static void syncCameraSectionCulling() {
        boolean preserve = false;
        ClientStageSession.Snapshot snapshot = ClientStageSession.active();
        if (snapshot != null && shouldOverrideCurrentStage()
                && StageWorlds.isStageLevel(Minecraft.getInstance().level)
                && isMounted(snapshot.instanceId())) {
            preserve = !snapshot.clientScene().voxyNearCulling();
        }
        setPreserveCameraSection(preserve);
    }

    private static void setPreserveCameraSection(boolean preserve) {
        if (!stageCompatResolved) {
            stageCompatResolved = true;
            try {
                preserveCameraSectionMethod = Class.forName(VOXY_STAGE_COMPAT)
                        .getMethod("setPreserveCameraSection", boolean.class);
            } catch (ClassNotFoundException | NoSuchMethodException ignored) {
                preserveCameraSectionMethod = null;
            }
        }
        Method method = preserveCameraSectionMethod;
        if (method == null) {
            if (preserve && !stageCompatWarningLogged) {
                stageCompatWarningLogged = true;
                LOGGER.warn("Voxy camera-section culling cannot be disabled; install the Dynamic Stage HDRS Voxy build");
            }
            return;
        }
        if (lastPreserveCameraSection != null && lastPreserveCameraSection == preserve) {
            return;
        }
        try {
            method.invoke(null, preserve);
            lastPreserveCameraSection = preserve;
        } catch (ReflectiveOperationException e) {
            if (!stageCompatWarningLogged) {
                stageCompatWarningLogged = true;
                LOGGER.warn("Could not configure Voxy camera-section culling: {}", rootMessage(e));
            }
        }
    }

    public static void leaveStageLevel() {
        setPreserveCameraSection(false);
        stageWorldEngine = null;
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
        stageWorldEngine = null;
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
        resolveBlockLookup(world);
        stageWorldEngine = world;
        LOGGER.info("Voxy opened stage LOD storage {}", current.pack.storageDirectory());
    }

    private static void resolveBlockLookup(Object world) throws ReflectiveOperationException {
        if (acquireSectionMethod != null && acquireSectionMethod.getDeclaringClass().isInstance(world)) {
            return;
        }
        Method acquire = world.getClass().getMethod("acquireIfExists",
                int.class, int.class, int.class, int.class);
        Class<?> section = acquire.getReturnType();
        acquireSectionMethod = acquire;
        sectionDataMethod = section.getMethod("_unsafeGetRawDataArray");
        releaseSectionMethod = section.getMethod("release");
    }

    private static Path findRocksDbPath(Object backend) throws ReflectiveOperationException {
        Object candidate = findRocksDbBackend(backend);
        Object db = findField(candidate.getClass(), "db").get(candidate);
        Object name = db.getClass().getMethod("getName").invoke(db);
        return Path.of(String.valueOf(name)).toAbsolutePath().normalize();
    }

    private static Object findRocksDbBackend(Object backend) throws ReflectiveOperationException {
        Object backends = backend.getClass().getMethod("collectAllBackends").invoke(backend);
        if (backends instanceof Iterable<?> iterable) {
            for (Object candidate : iterable) {
                if (candidate != null && candidate.getClass().getName().endsWith("RocksDBStorageBackend")) {
                    return candidate;
                }
            }
        }
        throw new IllegalStateException("Voxy storage config does not contain RocksDB");
    }

    private static boolean hasStoredSections(Object backend) throws ReflectiveOperationException {
        Object rocks = findRocksDbBackend(backend);
        Object db = findField(rocks.getClass(), "db").get(rocks);
        Object worldSections = findField(rocks.getClass(), "worldSections").get(rocks);
        Class<?> handleClass = Class.forName("org.rocksdb.ColumnFamilyHandle");
        Object iterator = db.getClass().getMethod("newIterator", handleClass).invoke(db, worldSections);
        try {
            iterator.getClass().getMethod("seekToFirst").invoke(iterator);
            return (boolean) iterator.getClass().getMethod("isValid").invoke(iterator);
        } finally {
            try {
                iterator.getClass().getMethod("close").invoke(iterator);
            } catch (ReflectiveOperationException ignored) {
            }
        }
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

    public record CurrentSource(Path storage, String worldId, boolean hasStoredSections,
                                boolean ingestEnabled) {
    }

    public static final class BlockLookup implements AutoCloseable {
        private static final long BLOCK_ID_MASK = ((1L << 20) - 1L) << 27;
        private static final long[] EMPTY = new long[0];

        private final Object world;
        private final Method acquire;
        private final Method data;
        private final Method release;
        private final Map<SectionKey, long[]> sections = new HashMap<>();
        private final List<Object> acquired = new ArrayList<>();
        private boolean closed;

        private BlockLookup(Object world, Method acquire, Method data, Method release) {
            this.world = world;
            this.acquire = acquire;
            this.data = data;
            this.release = release;
        }

        public boolean isSolid(int x, int y, int z) {
            if (closed) {
                throw new IllegalStateException("Voxy block lookup is closed");
            }
            SectionKey key = new SectionKey(Math.floorDiv(x, 32), Math.floorDiv(y, 32), Math.floorDiv(z, 32));
            long[] blocks = sections.get(key);
            if (blocks == null) {
                blocks = acquire(key);
                sections.put(key, blocks);
            }
            if (blocks == EMPTY) {
                return false;
            }
            int index = ((y & 31) << 10) | ((z & 31) << 5) | (x & 31);
            return (blocks[index] & BLOCK_ID_MASK) != 0L;
        }

        private long[] acquire(SectionKey key) {
            try {
                Object section = acquire.invoke(world, 0, key.x(), key.y(), key.z());
                if (section == null) {
                    return EMPTY;
                }
                acquired.add(section);
                Object raw = data.invoke(section);
                if (!(raw instanceof long[] blocks) || blocks.length != 32 * 32 * 32) {
                    throw new IllegalStateException("Voxy returned invalid level-0 section data");
                }
                return blocks;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Could not acquire a Voxy LOD section", e);
            }
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            RuntimeException failure = null;
            for (int index = acquired.size() - 1; index >= 0; index--) {
                try {
                    release.invoke(acquired.get(index));
                } catch (ReflectiveOperationException e) {
                    if (failure == null) {
                        failure = new IllegalStateException("Could not release a Voxy LOD section", e);
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            acquired.clear();
            sections.clear();
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record SectionKey(int x, int y, int z) {
    }
}
