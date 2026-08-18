package vibe.liteming.dynamicstage.stage;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

/** Bridges Loquat's structure snapshot support without making it a hard dependency. */
final class LoquatArenaCompat {
    private static final String AREA_MANAGER = "snownee.loquat.core.AreaManager";

    private LoquatArenaCompat() {
    }

    static void clearAreas(ServerLevel level, AABB bounds) throws IOException {
        try {
            Class<?> managerClass = Class.forName(AREA_MANAGER);
            Object manager = managerClass.getMethod("of", ServerLevel.class).invoke(null, level);
            managerClass.getMethod("removeAllInside", AABB.class).invoke(manager, bounds);
        } catch (ClassNotFoundException | NoSuchMethodException ignored) {
            // Loquat is optional, and later releases may no longer need this compatibility path.
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IOException("could not replace Loquat areas: " + cause.getMessage(), cause);
        } catch (ReflectiveOperationException e) {
            throw new IOException("could not replace Loquat areas", e);
        }
    }

}
