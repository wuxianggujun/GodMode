package tiiehenry.viewcontroller.injection.bridge;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import tiiehenry.viewcontroller.IGodModeManager;
import tiiehenry.viewcontroller.IObserver;
import tiiehenry.viewcontroller.injection.util.Logger;
import tiiehenry.viewcontroller.rule.ActRules;
import tiiehenry.viewcontroller.rule.AppRules;
import tiiehenry.viewcontroller.rule.ViewRule;

/**
 * 容器内 GodMode 的本地规则仓库（双向）。
 *
 * XServiceManager 的 binder 中继只在 system_server 里部署（initForSystemServer 拦截
 * addService("clipboard") 套一层 BinderDelegateService），沙盒进程里没有 system_server，
 * clipboard binder 从未被包装，XServiceManager.getService("godmode") 永远返回 null。
 * 于是这里改走文件：规则存成和 RuleExporter 一模一样的 manifest.json（ViewRule 数组）
 * 放在 <宿主 filesDir>/tina_godmode/<包名>.json，沙盒进程和宿主同 uid，直接可读可写。
 * 路径由 SandXposed 注入前写入 tina.host.filesdir 系统属性传进来；属性不存在说明不在
 * 我们的沙盒里（独立安装的 GodMode），load 返回 null 让调用方退回原来的 binder 路径。
 *
 * 实例不绑包名：所有读写方法的第一个参数就是 packageName，按参数定位文件即可。
 * 这样 GodModeManager 那个全局 singleton 在多分身共用一个宿主进程时也不会串包——
 * 每次调用都带着目标包名，不会拿到别的应用的规则。
 */
public final class LocalGodModeManager extends IGodModeManager.Stub {

    private static final String DIR_NAME = "tina_godmode";
    private static final String RULES_SUFFIX = ".json";
    private static final String DISABLED_SUFFIX = ".disabled";
    private static final String EDIT_MODE_FILE = "editmode";
    private static final String IMAGE_SUFFIX = ".webp";
    private static final int IMAGE_QUALITY = 80;
    private static final String TAG = "GodMode";

    private static volatile LocalGodModeManager sInstance;

    private final File baseDir;
    private final Map<String, List<IObserver>> observers = new ConcurrentHashMap<>();

    private LocalGodModeManager(File hostFilesDir) {
        this.baseDir = new File(hostFilesDir, DIR_NAME);
    }

    /**
     * 不在沙盒里（tina.host.filesdir 缺失）返回 null，调用方退回 Default()。
     * 包名参数保留给老调用方（GodModeManager.getDefault(pkg)），实例本身不认包名。
     */
    @Nullable
    public static LocalGodModeManager load(@Nullable String packageName) {
        if (sInstance != null) {
            return sInstance;
        }
        String hostFilesDir = System.getProperty("tina.host.filesdir");
        android.util.Log.d("GodMode", "LocalGM load pkg=" + packageName
                + " filesdir=" + hostFilesDir + " (null means not in sandbox)");
        if (TextUtils.isEmpty(hostFilesDir)) {
            return null;
        }
        synchronized (LocalGodModeManager.class) {
            if (sInstance == null) {
                sInstance = new LocalGodModeManager(new File(hostFilesDir));
            }
            return sInstance;
        }
    }

    // ---------------------------------------------------------------- read

    @Override
    public ActRules getRules(String packageName) {
        ActRules actRules = new ActRules();
        if (TextUtils.isEmpty(packageName)) {
            return actRules;
        }
        parseRulesFile(rulesFile(packageName), actRules);
        return actRules;
    }

    @Override
    public boolean isAppDisabled(String packageName) {
        return !TextUtils.isEmpty(packageName)
                && new File(baseDir, packageName + DISABLED_SUFFIX).exists();
    }

    @Override
    public String[] getDisabledApps() {
        List<String> disabled = new ArrayList<>();
        String[] files = baseDir.list();
        if (files == null) {
            return new String[0];
        }
        for (String name : files) {
            if (name.endsWith(DISABLED_SUFFIX)) {
                disabled.add(name.substring(0, name.length() - DISABLED_SUFFIX.length()));
            }
        }
        return disabled.toArray(new String[0]);
    }

    @Override
    public AppRules getAllRules() {
        AppRules appRules = new AppRules();
        String[] files = baseDir.list();
        if (files == null) {
            return appRules;
        }
        for (String name : files) {
            if (!name.endsWith(RULES_SUFFIX)) {
                continue;
            }
            String pkg = name.substring(0, name.length() - RULES_SUFFIX.length());
            ActRules actRules = new ActRules();
            if (parseRulesFile(new File(baseDir, name), actRules) && !actRules.isEmpty()) {
                appRules.put(pkg, actRules);
            }
        }
        return appRules;
    }

    @Override
    public boolean isInEditMode() {
        return new File(baseDir, EDIT_MODE_FILE).exists();
    }

    // ---------------------------------------------------------------- write

    @Override
    public void setEditMode(boolean enable) {
        File marker = new File(baseDir, EDIT_MODE_FILE);
        boolean changed = false;
        synchronized (this) {
            if (enable) {
                if (!baseDir.exists() && !baseDir.mkdirs()) {
                    return;
                }
                changed = !marker.exists() && createEmptyFile(marker);
            } else {
                changed = marker.exists() && marker.delete();
            }
        }
        if (changed) {
            notifyEditModeChanged(enable);
        }
    }

    @Override
    public boolean writeRule(String packageName, ViewRule viewRule, Bitmap snapshot) {
        if (TextUtils.isEmpty(packageName) || viewRule == null
                || TextUtils.isEmpty(viewRule.activityClass)
                || TextUtils.isEmpty(viewRule.viewClass)) {
            return false;
        }
        synchronized (this) {
            if (!prepareBaseDir()) {
                return false;
            }
            ActRules actRules = getRules(packageName);
            List<ViewRule> viewRules = actRules.get(viewRule.activityClass);
            if (viewRules == null) {
                viewRules = new ArrayList<>();
                actRules.put(viewRule.activityClass, viewRules);
            }
            viewRules.add(viewRule);

            // 快照存到宿主可读的目录，imagePath 直接落绝对路径，导出/预览原样可用。
            File imageFile = new File(baseDir,
                    packageName + "_" + System.currentTimeMillis() + IMAGE_SUFFIX);
            if (saveBitmapToFile(snapshot, imageFile)) {
                viewRule.imagePath = imageFile.getAbsolutePath();
            }
            return saveRules(packageName, actRules);
        }
    }

    @Override
    public boolean writeAllRule(String packageName, ActRules actRules) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        synchronized (this) {
            if (!prepareBaseDir()) {
                return false;
            }
            boolean ok = saveRules(packageName, actRules == null ? new ActRules() : actRules);
            if (ok) {
                notifyViewRuleChanged(packageName, getRules(packageName));
            }
            return ok;
        }
    }

    @Override
    public boolean updateRule(String packageName, ViewRule viewRule) {
        if (TextUtils.isEmpty(packageName) || viewRule == null
                || TextUtils.isEmpty(viewRule.activityClass)) {
            return false;
        }
        synchronized (this) {
            ActRules actRules = getRules(packageName);
            List<ViewRule> viewRules = actRules.get(viewRule.activityClass);
            if (viewRules == null) {
                viewRules = new ArrayList<>();
                actRules.put(viewRule.activityClass, viewRules);
            }
            boolean contains = false;
            for (ViewRule rule : viewRules) {
                if (viewRule.equalsSimple(rule)) {
                    contains = true;
                    rule.copy(viewRule);
                    break;
                }
            }
            if (!contains) {
                viewRules.add(viewRule);
            }
            boolean ok = saveRules(packageName, actRules);
            if (ok) {
                notifyViewRuleChanged(packageName, actRules);
            }
            return ok;
        }
    }

    @Override
    public boolean deleteRule(String packageName, ViewRule viewRule) {
        if (TextUtils.isEmpty(packageName) || viewRule == null
                || TextUtils.isEmpty(viewRule.activityClass)) {
            return false;
        }
        synchronized (this) {
            ActRules actRules = getRules(packageName);
            List<ViewRule> viewRules = actRules.get(viewRule.activityClass);
            if (viewRules == null) {
                return false;
            }
            if (!viewRules.remove(viewRule)) {
                return false;
            }
            if (viewRules.isEmpty()) {
                actRules.remove(viewRule.activityClass);
            }
            boolean ok = saveRules(packageName, actRules);
            if (ok) {
                notifyViewRuleChanged(packageName, actRules);
            }
            return ok;
        }
    }

    @Override
    public boolean deleteRules(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        synchronized (this) {
            File file = rulesFile(packageName);
            boolean removed = file.exists() && file.delete();
            if (removed) {
                notifyViewRuleChanged(packageName, new ActRules());
            }
            return removed;
        }
    }

    @Override
    public void setAppEnable(String packageName, boolean enable) {
        if (TextUtils.isEmpty(packageName)) {
            return;
        }
        File marker = new File(baseDir, packageName + DISABLED_SUFFIX);
        boolean changed;
        synchronized (this) {
            if (enable) {
                changed = marker.exists() && marker.delete();
            } else {
                if (!baseDir.exists() && !baseDir.mkdirs()) {
                    return;
                }
                changed = !marker.exists() && createEmptyFile(marker);
            }
        }
        if (changed) {
            notifyAppStatusChanged(packageName, enable);
        }
    }

    @Override
    public boolean writeBitmap(String packageName, String fileName, Bitmap snapshot) {
        if (TextUtils.isEmpty(fileName) || snapshot == null) {
            return false;
        }
        synchronized (this) {
            if (!prepareBaseDir()) {
                return false;
            }
            File target = resolvePath(fileName);
            return saveBitmapToFile(snapshot, target);
        }
    }

    @Override
    public ParcelFileDescriptor openImageFileDescriptor(String filePath) {
        File file = resolvePath(filePath);
        if (!file.exists() || !file.isFile()) {
            return null;
        }
        try {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- observer

    @Override
    public void addObserver(String packageName, IObserver observer) {
        if (TextUtils.isEmpty(packageName) || observer == null) {
            return;
        }
        synchronized (observers) {
            List<IObserver> list = observers.get(packageName);
            if (list == null) {
                list = new ArrayList<>();
                observers.put(packageName, list);
            }
            if (!list.contains(observer)) {
                list.add(observer);
            }
        }
    }

    @Override
    public void removeObserver(String packageName, IObserver observer) {
        if (TextUtils.isEmpty(packageName) || observer == null) {
            return;
        }
        synchronized (observers) {
            List<IObserver> list = observers.get(packageName);
            if (list != null) {
                list.remove(observer);
                if (list.isEmpty()) {
                    observers.remove(packageName);
                }
            }
        }
    }

    private void notifyViewRuleChanged(String packageName, ActRules actRules) {
        List<IObserver> list = copyObserversForPackage(packageName);
        for (IObserver observer : list) {
            try {
                observer.onViewRuleChanged(packageName, actRules);
            } catch (Throwable ignored) {
                dropObserver(observer);
            }
        }
    }

    private void notifyEditModeChanged(boolean enable) {
        // 编辑模式是全局开关：原 GodModeManagerService 用 RemoteCallbackList 广播给所有
        // 注册者（模块自己的 UI 注册成 "*"，被注入的 app 注册成自己的包名）。这里也得全广播，
        // 不然注入侧的 ManagerObserver 收不到，编辑 overlay 不会跟着开关刷新。
        for (IObserver observer : copyAllObservers()) {
            try {
                observer.onEditModeChanged(enable);
            } catch (Throwable ignored) {
                dropObserver(observer);
            }
        }
    }

    private void notifyAppStatusChanged(String packageName, boolean enable) {
        List<IObserver> list = copyObserversForPackage(packageName);
        for (IObserver observer : list) {
            try {
                observer.onAppStatusChanged(enable);
            } catch (Throwable ignored) {
                dropObserver(observer);
            }
        }
    }

    /**
     * 取出订阅了某个包的观察者：注册成目标包名的，加上注册成 "*" 的——原服务端
     * runBroadcastActionForPackage 的通配语义，模块自己的 UI 用 "*" 订阅所有包的变更。
     */
    private List<IObserver> copyObserversForPackage(@Nullable String packageName) {
        List<IObserver> all = new ArrayList<>();
        synchronized (observers) {
            addCopy(all, observers.get(packageName));
            if (!TextUtils.equals(packageName, "*")) {
                addCopy(all, observers.get("*"));
            }
        }
        return all;
    }

    private static void addCopy(List<IObserver> dst, @Nullable List<IObserver> src) {
        if (src != null) {
            dst.addAll(src);
        }
    }

    private List<IObserver> copyAllObservers() {
        List<IObserver> all = new ArrayList<>();
        synchronized (observers) {
            for (List<IObserver> list : observers.values()) {
                if (list != null) {
                    all.addAll(list);
                }
            }
        }
        return all;
    }

    private void dropObserver(IObserver observer) {
        synchronized (observers) {
            for (List<IObserver> list : observers.values()) {
                if (list != null) {
                    list.remove(observer);
                }
            }
        }
    }

    // ---------------------------------------------------------------- persistence

    private File rulesFile(String packageName) {
        return new File(baseDir, packageName + RULES_SUFFIX);
    }

    private File resolvePath(String path) {
        File file = new File(path);
        return file.isAbsolute() ? file : new File(baseDir, path);
    }

    private boolean prepareBaseDir() {
        return baseDir.exists() || baseDir.mkdirs();
    }

    private boolean createEmptyFile(File file) {
        try {
            FileOutputStream out = new FileOutputStream(file);
            out.close();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private boolean saveBitmapToFile(Bitmap bitmap, File file) {
        if (bitmap == null) {
            return false;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file);
            if (!bitmap.compress(Bitmap.CompressFormat.WEBP, IMAGE_QUALITY, out)) {
                throw new IOException("bitmap compress fail " + file.getAbsolutePath());
            }
            return true;
        } catch (IOException e) {
            if (file.exists()) {
                file.delete();
            }
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 原子写：先写临时文件再改名，避免宿主读到一个写一半的 json。
     * 临时文件后缀绝不能用 .json——getAllRules 扫的就是 baseDir 里 .json 结尾的文件，
     * 重名会让写一半的 rulesNNN.json 被当成包名 rulesNNN 的规则返回出去。
     */
    private boolean saveRules(String packageName, ActRules actRules) {
        File target = rulesFile(packageName);
        File tmp = null;
        Writer writer = null;
        try {
            tmp = File.createTempFile("rules", ".tmp", baseDir);
            writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8);
            new Gson().toJson(flatten(actRules), ViewRule[].class, writer);
            writer.flush();
            writer.close();
            writer = null;
            if (!tmp.renameTo(target)) {
                // 同目录改名一般不会失败，真失败了就把临时文件清掉，让上层知道没存成。
                tmp.delete();
                return false;
            }
            return true;
        } catch (Throwable e) {
            if (tmp != null && tmp.exists()) {
                tmp.delete();
            }
            return false;
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static ViewRule[] flatten(ActRules actRules) {
        Collection<List<ViewRule>> buckets = actRules.values();
        List<ViewRule> all = new ArrayList<>();
        for (List<ViewRule> bucket : buckets) {
            if (bucket != null) {
                all.addAll(bucket);
            }
        }
        return all.toArray(new ViewRule[0]);
    }

    /**
     * 解析失败返回 false（容器留空），绝不因为规则文件坏了把目标进程搞崩。
     * ViewRule.equals/hashCode 直接 dereference activityClass 和 viewClass（final 字段），
     * Gson 走 UnsafeAllocator 绕过构造函数，缺字段时它们是 null，进 applyRuleBatch 的
     * 缓存 key 立刻 NPE。depth 也是 final，缺字段时是 null 且没法补——findViewByDepth 的
     * for-each 会 NPE，所以空 depth 的规则直接丢弃（规则导出时 depth 必填，不会误伤）。
     */
    private boolean parseRulesFile(File rulesFile, ActRules out) {
        if (!rulesFile.exists()) {
            return false;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(rulesFile);
            ViewRule[] list = new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), ViewRule[].class);
            in.close();
            in = null;
            if (list == null) {
                return false;
            }
            Map<String, List<ViewRule>> bucketed = new HashMap<>();
            for (ViewRule rule : list) {
                if (rule == null
                        || TextUtils.isEmpty(rule.activityClass)
                        || TextUtils.isEmpty(rule.viewClass)
                        || rule.depth == null) {
                    continue;
                }
                bucketed.computeIfAbsent(rule.activityClass, k -> new ArrayList<>()).add(rule);
            }
            out.putAll(bucketed);
            return true;
        } catch (Throwable e) {
            // WARN 默认可输出（不用 setprop log.tag.GodMode）。损坏的规则文件会让这个应用
            // 的规则一条都不加载，用户视角是「面板写的规则重启后全消失」，不吭声的话极难定位。
            Logger.w(TAG, "parse rules file fail, all rules for this app dropped: " + rulesFile, e);
            return false;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
