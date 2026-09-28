package tiiehenry.viewcontroller.injection.bridge;

import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tiiehenry.viewcontroller.IGodModeManager;
import tiiehenry.viewcontroller.IObserver;
import tiiehenry.viewcontroller.rule.ActRules;
import tiiehenry.viewcontroller.rule.AppRules;
import tiiehenry.viewcontroller.rule.ViewRule;

/**
 * 容器内 GodMode 的本地规则仓库。
 *
 * XServiceManager 的 binder 中继只在 system_server 里部署（initForSystemServer 拦截
 * addService("clipboard") 套一层 BinderDelegateService），沙盒进程里没有 system_server，
 * clipboard binder 从未被包装，XServiceManager.getService("godmode") 永远返回 null。
 * 于是 GodModeManager 拿到的是 IGodModeManager.Default() —— 纯空实现，规则一条都进不去。
 *
 * 这里改走文件：宿主把规则导出成和 RuleExporter 一模一样的 manifest.json（ViewRule 数组）
 * 推到 <宿主 filesDir>/tina_godmode/<包名>.json，沙盒进程和宿主同 uid，直接可读。
 * 路径由 SandXposed 注入前写入 tina.host.filesdir 系统属性传进来；属性不存在说明不在
 * 我们的沙盒里（独立安装的 GodMode），返回 null 让调用方退回原来的 Default()。
 *
 * 只实现读路径（getRules/isAppDisabled/getDisabledApps/getAllRules），编辑、观察者、
 * 所有写方法全部空实现 —— 容器里没有管理端 UI，规则在进程启动时加载一次就够。
 */
public final class LocalGodModeManager extends IGodModeManager.Stub {

    private static final String DIR_NAME = "tina_godmode";
    private static final String RULES_SUFFIX = ".json";
    private static final String DISABLED_SUFFIX = ".disabled";

    private static final Map<String, LocalGodModeManager> sCache = new HashMap<>();

    private final String packageName;
    private final ActRules actRules;
    private final boolean disabled;

    private LocalGodModeManager(String packageName, ActRules actRules, boolean disabled) {
        this.packageName = packageName;
        this.actRules = actRules;
        this.disabled = disabled;
    }

    /**
     * 读一次，进程内缓存。任何解析失败都返回 null —— 调用方退回 Default()，
     * 绝不因为规则文件坏了把目标进程搞崩。
     */
    @Nullable
    public static synchronized LocalGodModeManager load(@Nullable String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return null;
        }
        LocalGodModeManager cached = sCache.get(packageName);
        if (cached != null) {
            return cached;
        }
        String hostFilesDir = System.getProperty("tina.host.filesdir");
        if (TextUtils.isEmpty(hostFilesDir)) {
            return null;
        }
        File rulesFile = new File(new File(hostFilesDir, DIR_NAME), packageName + RULES_SUFFIX);
        if (!rulesFile.exists()) {
            return null;
        }
        ActRules actRules = parseRules(rulesFile);
        if (actRules == null) {
            return null;
        }
        boolean disabled = new File(new File(hostFilesDir, DIR_NAME),
                packageName + DISABLED_SUFFIX).exists();
        LocalGodModeManager manager = new LocalGodModeManager(packageName, actRules, disabled);
        sCache.put(packageName, manager);
        return manager;
    }

    @Nullable
    private static ActRules parseRules(File rulesFile) {
        FileInputStream in = null;
        try {
            in = new FileInputStream(rulesFile);
            ViewRule[] list = new Gson().fromJson(
                    new InputStreamReader(in, StandardCharsets.UTF_8), ViewRule[].class);
            if (list == null) {
                return null;
            }
            // ViewRule.equals/hashCode 直接 dereference activityClass 和 viewClass（final 字段），
            // Gson 走 UnsafeAllocator 绕过构造函数，缺字段时它们是 null，进 applyRuleBatch 的
            // 缓存 key 立刻 NPE。depth 也是 final，缺字段时是 null 且没法补——findViewByDepth 的
            // for-each 会 NPE，所以空 depth 的规则直接丢弃（规则导出时 depth 必填，不会误伤）。
            // 按 activityClass 分桶，和 RuleImporter 同样的归组规则；
            // ActRules 继承 HashMap，putAll 直接喂进去。
            ActRules actRules = new ActRules();
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
            actRules.putAll(bucketed);
            return actRules;
        } catch (Throwable e) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public ActRules getRules(String packageName) {
        return actRules;
    }

    @Override
    public boolean isAppDisabled(String packageName) {
        return disabled;
    }

    @Override
    public String[] getDisabledApps() {
        return disabled ? new String[]{packageName} : new String[0];
    }

    @Override
    public AppRules getAllRules() {
        AppRules appRules = new AppRules();
        if (!actRules.isEmpty()) {
            appRules.put(this.packageName, actRules);
        }
        return appRules;
    }

    @Override
    public void setEditMode(boolean enable) {
    }

    @Override
    public boolean isInEditMode() {
        return false;
    }

    @Override
    public void addObserver(String packageName, IObserver observer) {
    }

    @Override
    public void removeObserver(String packageName, IObserver observer) {
    }

    @Override
    public boolean writeBitmap(String packageName, String fileName, Bitmap snapshot) {
        return false;
    }

    @Override
    public boolean writeAllRule(String packageName, ActRules actRules) {
        return false;
    }

    @Override
    public boolean writeRule(String packageName, ViewRule viewRule, Bitmap bitmap) {
        return false;
    }

    @Override
    public boolean updateRule(String packageName, ViewRule viewRule) {
        return false;
    }

    @Override
    public boolean deleteRule(String packageName, ViewRule viewRule) {
        return false;
    }

    @Override
    public boolean deleteRules(String packageName) {
        return false;
    }

    @Override
    public void setAppEnable(String packageName, boolean enable) {
    }

    @Override
    public ParcelFileDescriptor openImageFileDescriptor(String filePath) {
        return null;
    }
}
