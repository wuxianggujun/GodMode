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

import java.io.File;

import tiiehenry.viewcontroller.BuildConfig;

public class GmResources {

    private static Resources GMResources;

    private static Resources getGmResource(Context context) throws PackageManager.NameNotFoundException {
        if (GMResources == null) {
            try {
                GMResources = context.createPackageContext(BuildConfig.APPLICATION_ID, 0).getResources();
            } catch (PackageManager.NameNotFoundException e) {
                // 沙盒里模块不是「已安装的虚拟包」，createPackageContext 必然失败，长按必然崩 guest。
                // 但模块的类已经被 SandXposed 从模块自己的 APK 里加载进来了，顺着 classloader 把那个
                // APK 路径挖出来，用 AssetManager.addAssetPath 重建一份 Resources，绕开 PackageManager。
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

    // findModuleApkPath 挖出来的路径在旧构建上可能是反检测伪装路径（/data/app/<pkg>-base64/base.apk），
    // 那个目录根本不存在。优先用能打开的那一份：先看 classloader 里的路径在不在，不在就问引擎要真实路径。
    private static String findUsableModuleApkPath() {
        String fromClassLoader = findModuleApkPath();
        if (fromClassLoader != null && new File(fromClassLoader).exists()) {
            return fromClassLoader;
        }
        return getEngineModuleApkPath();
    }

    // VEnvironment.getPackageFile(pkg) 是引擎里「包名 → 真实 APK」的权威映射（virtual/data/app/<pkg>/base.apk）。
    // godmode 编译期看不到 commonSdk，但模块类在 guest 进程里由宿主 classloader 加载，运行期反射即可。
    private static String getEngineModuleApkPath() {
        try {
            Class<?> vEnv = XposedHelpers.findClass(
                    "com.lody.virtual.os.VEnvironment", GmResources.class.getClassLoader());
            File apkFile = (File) XposedHelpers.callStaticMethod(vEnv, "getPackageFile", BuildConfig.APPLICATION_ID);
            if (apkFile != null && apkFile.exists()) {
                return apkFile.getPath();
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
    static CharSequence getTextSafe(Context context, int id) {
        try {
            return getGmResource(context).getText(id);
        } catch (Throwable ignored) {
            return "";
        }
    }

    static String getStringSafe(Context context, int id, Object... formatArgs) {
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
