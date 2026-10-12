package tiiehenry.viewcontroller.injection.bridge;

import android.util.Log;
import androidx.annotation.Nullable;
import de.robv.android.xposed.XposedBridge;
import java.io.File;
import java.lang.reflect.InvocationTargetException;

/** Optional container integration; the standalone module still uses its Binder service. */
final class ContainerRuleStorage {
    private static final String BRIDGE =
            "com.wuxianggujun.tinavirtualapp.engine.virtual.sandxposed.ModuleStorageBridge";

    private ContainerRuleStorage() { }

    @Nullable
    static File resolve() {
        try {
            Class<?> bridge = Class.forName(BRIDGE, false, XposedBridge.class.getClassLoader());
            Object result = bridge.getMethod("getGodModeRulesDirectory").invoke(null);
            if (result == null) return null;
            if (!(result instanceof File) || !((File) result).isAbsolute()) {
                throw new IllegalStateException("Invalid rule storage directory");
            }
            return (File) result;
        } catch (ClassNotFoundException | NoClassDefFoundError standalone) {
            return null;
        } catch (InvocationTargetException error) {
            Log.w("GodMode", "Container rule storage unavailable", error.getCause());
            return null;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w("GodMode", "Container rule storage contract failed", error);
            return null;
        }
    }
}
