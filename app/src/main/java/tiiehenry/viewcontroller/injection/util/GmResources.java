package tiiehenry.viewcontroller.injection.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import dalvik.system.BaseDexClassLoader;
import de.robv.android.xposed.XposedHelpers;

import tiiehenry.viewcontroller.BuildConfig;

public class GmResources {

    private static Resources GMResources;

    private static Resources getGmResource(Context context) throws PackageManager.NameNotFoundException {
        if (GMResources == null) {
            try {
                GMResources = context.createPackageContext(BuildConfig.APPLICATION_ID, 0).getResources();
            } catch (PackageManager.NameNotFoundException e) {
                // 模块包在当前 Context 中不可见时，使用模块自身 ClassLoader 的 APK 资源。
                String apkPath = findModuleApkPath();
                if (apkPath == null) {
                    throw e;
                }
                GMResources = loadResourcesFromApk(context, apkPath);
            }
        }
        return GMResources;
    }

    // XposedInit.loadModule 用 new DexClassLoader(modulePath, ...) 加载模块，BaseDexClassLoader 的
    // pathList.dexElements 里第一个 dexFile.mFileName 就是那个 APK 的路径。
    private static String findModuleApkPath() {
        ClassLoader cl = GmResources.class.getClassLoader();
        if (!(cl instanceof BaseDexClassLoader)) {
            return null;
        }
        try {
            Object pathList = XposedHelpers.getObjectField(cl, "pathList");
            Object[] elements = (Object[]) XposedHelpers.getObjectField(pathList, "dexElements");
            for (Object element : elements) {
                Object dexFile = XposedHelpers.getObjectField(element, "dexFile");
                if (dexFile == null) {
                    continue;
                }
                String name = (String) XposedHelpers.getObjectField(dexFile, "mFileName");
                if (name != null && name.endsWith(".apk")) {
                    return name;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @SuppressLint("PrivateApi")
    private static Resources loadResourcesFromApk(Context context, String apkPath) throws PackageManager.NameNotFoundException {
        AssetManager am;
        try {
            am = (AssetManager) XposedHelpers.newInstance(AssetManager.class);
        } catch (Throwable e) {
            throw new PackageManager.NameNotFoundException("cannot create AssetManager for " + apkPath + ": " + e);
        }
        // addAssetPath 返回 int cookie，<=0 即失败；不校验就会造出一份半空的 Resources，
        // 后面 getText 才炸 NotFoundException，比直接报错更难查。
        if (!ensureAssetPath(am, apkPath) || !ensureAssetPath(am, "/system/framework/framework-res.apk")) {
            throw new PackageManager.NameNotFoundException("addAssetPath failed for " + apkPath);
        }
        Resources host = context.getResources();
        DisplayMetrics dm = new DisplayMetrics();
        dm.setTo(host.getDisplayMetrics());
        Configuration config = new Configuration(host.getConfiguration());
        return new Resources(am, dm, config);
    }

    private static boolean ensureAssetPath(AssetManager am, String path) {
        try {
            Object cookie = XposedHelpers.callMethod(am, "addAssetPath", path);
            return cookie instanceof Integer && (Integer) cookie > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // 同包的 GmLayoutInflater 要拿这份 Resources 去充气模块布局；彻底拿不到时返回 null，
    // 让调用方降级，而不是把 NameNotFoundException 抛到 guest 主线程上。
    static Resources getGmResourceSafe(Context context) {
        try {
            return getGmResource(context);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    // 主线程上的控件初始化（CancelView 之类）拿不到资源时不能让 NotFoundException 冒泡出去崩 guest。
    public static CharSequence getTextSafe(Context context, int id) {
        try {
            return getGmResource(context).getText(id);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String getStringSafe(Context context, int id, Object... formatArgs) {
        try {
            return getGmResource(context).getString(id, formatArgs);
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static int getColor(Context context, int id) throws Resources.NotFoundException {
        try {
            return getGmResource(context).getColor(id);
        } catch (PackageManager.NameNotFoundException e) {
            throw new Resources.NotFoundException("get resources fail GodMode package may be not installed?");
        }
    }

    @SuppressLint("UseCompatLoadingForDrawables")
    public static Drawable getDrawable(Context context, int id) throws Resources.NotFoundException {
        try {
            return getGmResource(context).getDrawable(id);
        } catch (PackageManager.NameNotFoundException e) {
            throw new Resources.NotFoundException("get resources fail GodMode package may be not installed?");
        }
    }

    public static CharSequence getText(Context context, int id) throws Resources.NotFoundException {
        try {
            return getGmResource(context).getText(id);
        } catch (PackageManager.NameNotFoundException e) {
            throw new Resources.NotFoundException("get resources fail GodMode package may be not installed?");
        }
    }

    public static String getString(Context context, int id) throws Resources.NotFoundException {
        try {
            return getGmResource(context).getString(id);
        } catch (PackageManager.NameNotFoundException e) {
            throw new Resources.NotFoundException("get resources fail GodMode package may be not installed?");
        }
    }

    public static String getString(Context context, int id, Object... formatArgs) throws Resources.NotFoundException {
        try {
            return getGmResource(context).getString(id, formatArgs);
        } catch (PackageManager.NameNotFoundException e) {
            throw new Resources.NotFoundException("get resources fail GodMode package may be not installed?");
        }
    }
}
