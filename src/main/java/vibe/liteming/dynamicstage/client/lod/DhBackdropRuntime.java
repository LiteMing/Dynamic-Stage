package vibe.liteming.dynamicstage.client.lod;

import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vibe.liteming.dynamicstage.client.stage.ClientStageSession;
import vibe.liteming.dynamicstage.world.StageWorlds;

import javax.annotation.Nullable;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.UUID;

/** Soft-dependency integration that keeps data loading and rendering inside Distant Horizons. */
public final class DhBackdropRuntime {

    private static final Logger LOGGER = LoggerFactory.getLogger(DhBackdropRuntime.class);
    private static final String SAVE_OVERRIDE =
            "com.seibel.distanthorizons.api.interfaces.override.levelHandling.IDhApiSaveStructure";
    private static final String DH_API = "com.seibel.distanthorizons.api.DhApi";
    private static final String SHARED_API = "com.seibel.distanthorizons.core.api.internal.SharedApi";
    private static final String[] CLIENT_WRAPPERS = {
            "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper_forge",
            "com.seibel.distanthorizons.common.wrappers.minecraft.MinecraftClientWrapper"
    };

    @Nullable private static volatile Mounted mounted;
    @Nullable private static Object saveOverrideProxy;
    @Nullable private static Object delegatedSaveOverride;
    private static int saveOverridePriority = 10;
    private static boolean overrideRegistered;
    private static boolean priorReadOnly;
    private static boolean changedReadOnly;
    private static boolean stageLevelBound;
    @Nullable private static Object boundWorld;
    @Nullable private static Object boundClientWrapper;
    @Nullable private static Object boundServerWrapper;

    private DhBackdropRuntime() {
    }

    public static Result mount(ClientStageSession.Snapshot snapshot) {
        Mounted previous = mounted;
        try {
            if (!net.minecraftforge.fml.ModList.get().isLoaded("distanthorizons")) {
                return Result.failure("Distant Horizons is not installed");
            }
            verifyDhVersion();
            LodPackRegistry.DhPack pack = LodPackRegistry.loadDh(snapshot.lodPackId());
            registerSaveOverride();
            if (previous != null && previous.pack.id().equals(pack.id())) {
                mounted = new Mounted(snapshot.instanceId(), pack);
                return Result.success();
            }
            mounted = new Mounted(snapshot.instanceId(), pack);
            stageLevelBound = false;
            if (previous == null) {
                setReadOnly(true);
            }
            if (previous == null || !previous.pack.id().equals(pack.id())) {
                LOGGER.info("Prepared DH LOD pack {} from {}", pack.id(), pack.dhDirectory());
            }
            return Result.success();
        } catch (Throwable e) {
            mounted = previous;
            if (previous == null) {
                restoreReadOnly();
                unregisterSaveOverride();
            }
            LOGGER.warn("Could not mount DH stage backdrop: {}", e.toString());
            return Result.failure(rootMessage(e));
        }
    }

    public static void unmount() {
        Mounted previous = mounted;
        mounted = null;
        stageLevelBound = false;
        if (previous != null) {
            try {
                if (boundWorld != null && boundClientWrapper != null) {
                    unloadBoundDhLevel();
                } else if (Minecraft.getInstance().level != null
                        && StageWorlds.isStageLevel(Minecraft.getInstance().level)) {
                    unloadCurrentDhLevel();
                }
            } catch (Throwable e) {
                LOGGER.warn("Could not unload the DH stage backdrop: {}", e.toString());
            }
        }
        restoreReadOnly();
        unregisterSaveOverride();
    }

    public static boolean isMounted(UUID instanceId) {
        Mounted current = mounted;
        return current != null && current.instanceId.equals(instanceId);
    }

    public static boolean needsStageActivation(UUID instanceId) {
        return isMounted(instanceId) && !stageLevelBound;
    }

    public static Result activateStage(UUID instanceId) {
        if (!isMounted(instanceId)) {
            return Result.failure("The prepared DH LOD package is no longer active");
        }
        if (stageLevelBound) {
            return Result.success();
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null || !StageWorlds.isStageLevel(minecraft.level)) {
                return Result.failure("The client has not entered the Dynamic Stage level");
            }
            reloadCurrentDhLevel();
            stageLevelBound = true;
            Mounted current = mounted;
            if (current != null) {
                LOGGER.info("Activated DH LOD pack {} for stage instance {}",
                        current.pack.id(), current.instanceId);
            }
            return Result.success();
        } catch (Throwable e) {
            LOGGER.debug("DH stage backdrop is not ready yet: {}", e.toString());
            return Result.failure(rootMessage(e));
        }
    }

    @Nullable
    static File overrideSaveFolder(Object currentFilePath, Object levelWrapper) throws ReflectiveOperationException {
        Mounted current = mounted;
        if (current != null && isStageWrapper(levelWrapper)) {
            return current.pack.dhDirectory().toFile();
        }
        Object delegate = delegatedSaveOverride;
        if (delegate == null) {
            return null;
        }
        Object delegated = findMethod(delegate.getClass(), "overrideFilePath", 2)
                .invoke(delegate, currentFilePath, levelWrapper);
        return delegated instanceof File file ? file : null;
    }

    private static void registerSaveOverride() throws ReflectiveOperationException {
        if (overrideRegistered) {
            return;
        }
        Class<?> overrideClass = Class.forName(SAVE_OVERRIDE);
        Class<?> dhApiClass = Class.forName(DH_API);
        Object injector = dhApiClass.getField("overrides").get(null);
        Method get = findMethod(injector.getClass(), "get", 1);
        Object delegate = get.invoke(injector, overrideClass);
        int priority = 10;
        if (delegate != null) {
            int delegatePriority = ((Number) delegate.getClass().getMethod("getPriority").invoke(delegate)).intValue();
            if (delegatePriority == Integer.MAX_VALUE) {
                throw new IllegalStateException("DH save override priority is already exhausted");
            }
            priority = Math.max(priority, delegatePriority + 1);
        }
        delegatedSaveOverride = delegate;
        saveOverridePriority = priority;
        Object proxy = Proxy.newProxyInstance(overrideClass.getClassLoader(), new Class<?>[]{overrideClass},
                (ignored, method, args) -> switch (method.getName()) {
                    case "overrideFilePath" -> overrideSaveFolder(args[0], args[1]);
                    case "getPriority" -> saveOverridePriority;
                    case "toString" -> "DynamicStageDhSaveOverride";
                    case "hashCode" -> System.identityHashCode(ignored);
                    case "equals" -> ignored == args[0];
                    default -> null;
                });
        Method bind = findMethod(injector.getClass(), "bind", 2);
        bind.invoke(injector, overrideClass, proxy);
        saveOverrideProxy = proxy;
        overrideRegistered = true;
    }

    private static void verifyDhVersion() throws ReflectiveOperationException {
        String version = String.valueOf(Class.forName(DH_API).getMethod("getModVersion").invoke(null));
        if (!version.equals(LodPackRegistry.DH_VERSION)
                && !version.startsWith(LodPackRegistry.DH_VERSION + '.')) {
            throw new IllegalStateException("LOD pack requires Distant Horizons "
                    + LodPackRegistry.DH_VERSION + ".x, found " + version);
        }
    }

    private static void unregisterSaveOverride() {
        Object proxy = saveOverrideProxy;
        if (!overrideRegistered || proxy == null) {
            return;
        }
        try {
            Class<?> overrideClass = Class.forName(SAVE_OVERRIDE);
            Object injector = Class.forName(DH_API).getField("overrides").get(null);
            Method unbind = findMethod(injector.getClass(), "unbind", 2);
            unbind.invoke(injector, overrideClass, proxy);
            saveOverrideProxy = null;
            delegatedSaveOverride = null;
            saveOverridePriority = 10;
            overrideRegistered = false;
        } catch (Throwable e) {
            LOGGER.warn("Could not unregister the DH save override: {}", e.toString());
        }
    }

    private static boolean isStageWrapper(Object wrapper) {
        if (wrapper == null) {
            return false;
        }
        String stageDimension = StageWorlds.STG_STAGE.location().toString();
        try {
            String dimensionName = String.valueOf(wrapper.getClass().getMethod("getDimensionName").invoke(wrapper));
            return stageDimension.equals(dimensionName);
        } catch (ReflectiveOperationException ignored) {
            try {
                String id = String.valueOf(wrapper.getClass().getMethod("getDhIdentifier").invoke(wrapper));
                return stageDimension.equals(id) || id.endsWith("@" + stageDimension);
            } catch (ReflectiveOperationException e) {
                return false;
            }
        }
    }

    private static void setReadOnly(boolean readOnly) throws ReflectiveOperationException {
        Object worldProxy = worldProxy();
        if (worldProxy == null) {
            throw new IllegalStateException("Distant Horizons has not initialized its world API");
        }
        if (!Boolean.TRUE.equals(worldProxy.getClass().getMethod("worldLoaded").invoke(worldProxy))) {
            throw new IllegalStateException("Distant Horizons has not loaded the current world");
        }
        Method getReadOnly = worldProxy.getClass().getMethod("getReadOnly");
        priorReadOnly = Boolean.TRUE.equals(getReadOnly.invoke(worldProxy));
        if (priorReadOnly != readOnly) {
            worldProxy.getClass().getMethod("setReadOnly", boolean.class).invoke(worldProxy, readOnly);
            changedReadOnly = true;
        }
    }

    private static void restoreReadOnly() {
        if (!changedReadOnly) {
            return;
        }
        try {
            Object worldProxy = worldProxy();
            if (worldProxy != null) {
                worldProxy.getClass().getMethod("setReadOnly", boolean.class).invoke(worldProxy, priorReadOnly);
            }
        } catch (Throwable e) {
            LOGGER.warn("Could not restore DH read-only state: {}", e.toString());
        } finally {
            changedReadOnly = false;
        }
    }

    @Nullable
    private static Object worldProxy() throws ReflectiveOperationException {
        Class<?> delayed = Class.forName(DH_API + "$Delayed");
        return delayed.getField("worldProxy").get(null);
    }

    private static void reloadCurrentDhLevel() throws ReflectiveOperationException {
        Object[] pair = currentWorldAndWrapper();
        if (pair == null) {
            throw new IllegalStateException("Distant Horizons has no wrapper for the current stage level");
        }
        Object world = pair[0];
        Object clientWrapper = pair[1];
        Object serverWrapper = tryGetServerWrapper(clientWrapper);
        boundWorld = world;
        boundClientWrapper = clientWrapper;
        boundServerWrapper = serverWrapper;
        invokeCompatible(world, "unloadLevel", clientWrapper);
        if (serverWrapper != null) {
            invokeCompatible(world, "unloadLevel", serverWrapper);
        }
        invalidateSaveFolder(world, clientWrapper);
        if (serverWrapper != null) {
            invalidateSaveFolder(world, serverWrapper);
            requireLoaded(invokeCompatible(world, "getOrLoadLevel", serverWrapper));
        }
        requireLoaded(invokeCompatible(world, "getOrLoadLevel", clientWrapper));
    }

    private static void unloadCurrentDhLevel() throws ReflectiveOperationException {
        Object[] pair = currentWorldAndWrapper();
        if (pair == null) {
            return;
        }
        boundWorld = pair[0];
        boundClientWrapper = pair[1];
        boundServerWrapper = tryGetServerWrapper(pair[1]);
        unloadBoundDhLevel();
    }

    private static void unloadBoundDhLevel() throws ReflectiveOperationException {
        Object world = boundWorld;
        Object clientWrapper = boundClientWrapper;
        Object serverWrapper = boundServerWrapper;
        if (world == null || clientWrapper == null) {
            return;
        }
        invokeCompatible(world, "unloadLevel", clientWrapper);
        if (serverWrapper != null) {
            invokeCompatible(world, "unloadLevel", serverWrapper);
        }
        invalidateSaveFolder(world, clientWrapper);
        if (serverWrapper != null) {
            invalidateSaveFolder(world, serverWrapper);
        }
        boundWorld = null;
        boundClientWrapper = null;
        boundServerWrapper = null;
    }

    @Nullable
    private static Object[] currentWorldAndWrapper() throws ReflectiveOperationException {
        Object world = Class.forName(SHARED_API).getMethod("tryGetDhClientWorld").invoke(null);
        if (world == null) {
            return null;
        }
        Object wrapper = null;
        for (String name : CLIENT_WRAPPERS) {
            try {
                Class<?> wrapperClass = Class.forName(name);
                Object instance = wrapperClass.getField("INSTANCE").get(null);
                wrapper = instance.getClass().getMethod("getWrappedClientLevel").invoke(instance);
                break;
            } catch (ClassNotFoundException ignored) {
                // Try the next supported DH layout.
            }
        }
        return wrapper == null ? null : new Object[]{world, wrapper};
    }

    private static Object invokeCompatible(Object target, String name, Object argument)
            throws ReflectiveOperationException {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isInstance(argument)) {
                return method.invoke(target, argument);
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + '.' + name);
    }

    private static void requireLoaded(@Nullable Object level) {
        if (level == null) {
            throw new IllegalStateException("Distant Horizons did not load the stage level");
        }
    }

    @Nullable
    private static Object tryGetServerWrapper(Object clientWrapper) throws ReflectiveOperationException {
        try {
            return clientWrapper.getClass().getMethod("tryGetServerSideWrapper").invoke(clientWrapper);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static void invalidateSaveFolder(Object world, Object wrapper) throws ReflectiveOperationException {
        Field saveStructureField = findField(world.getClass(), "saveStructure");
        Object saveStructure = saveStructureField.get(world);
        Field cacheField = findField(saveStructure.getClass(), "levelWrapperToFileMap");
        Object cache = cacheField.get(saveStructure);
        if (!(cache instanceof Map<?, ?> map)) {
            throw new IllegalStateException("DH save folder cache has an unsupported type");
        }
        map.remove(wrapper);
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

    private static Method findMethod(Class<?> type, String name, int parameters) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters) {
                return method;
            }
        }
        throw new NoSuchMethodException(type.getName() + '.' + name);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    private record Mounted(UUID instanceId, LodPackRegistry.DhPack pack) {
    }

    public record Result(boolean ready, String error) {
        public static Result success() {
            return new Result(true, "");
        }

        public static Result failure(String error) {
            return new Result(false, error);
        }
    }
}
