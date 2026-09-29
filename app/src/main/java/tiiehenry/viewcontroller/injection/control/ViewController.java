package tiiehenry.viewcontroller.injection.control;

import static tiiehenry.viewcontroller.GodModeApplication.TAG;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Pair;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;

import tiiehenry.viewcontroller.injection.util.Logger;
import tiiehenry.viewcontroller.rule.ViewRule;
import tiiehenry.viewcontroller.util.DisplayUtils;
import tiiehenry.viewcontroller.util.Preconditions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 */

public final class ViewController {

    private final static SparseArray<Pair<WeakReference<View>, ViewProperty>> blockedViewCache = new SparseArray<>();

    // 版本号在一次运行里不会变，每帧反射 BuildConfig 太贵；按 activity 缓存。
    private final static WeakHashMap<Activity, Integer> sVersionCodeCache = new WeakHashMap<>();

    // 布局每帧都触发 onGlobalLayout（宿主 Compose 自身逐帧 invalidation），
    // 批次本身必须每帧跑（目标 app 会把视图改回去），但日志不能每帧刷 logd。
    private static final long BATCH_LOG_THROTTLE_MS = 500L;
    private static long sLastBatchLogTime;

    private static int getVersionCodeCached(Activity activity) {
        synchronized (sVersionCodeCache) {
            Integer cached = sVersionCodeCache.get(activity);
            if (cached != null && cached > 0) {
                return cached;
            }
        }
        int code = getVersionCode(activity);
        if (code > 0) {
            synchronized (sVersionCodeCache) {
                sVersionCodeCache.put(activity, code);
            }
        }
        return code;
    }

    private static int getVersionCode(Activity activity) {
        boolean strictMode = false;
        try {
            ClassLoader cl = activity.getClassLoader();
            Class<?> BuildConfigClass = cl.loadClass(activity.getPackageName() + ".BuildConfig");
            return BuildConfigClass.getField("VERSION_CODE").getInt(null);
        } catch (Exception ignore) {
            try {
                PackageInfo packageInfo = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
                return packageInfo.versionCode;
            } catch (PackageManager.NameNotFoundException e) {
                Logger.w(TAG, "See what happened!", e);
            }
        }
        return -1;
    }

    public static void applyRuleBatch(Activity activity, List<ViewRule> rules) {
        applyRuleBatch(activity, rules, false);
    }

    // logNow=true 跳过限速：规则变更等低频路径需要立刻看到完整日志。
    public static void applyRuleBatch(Activity activity, List<ViewRule> rules, boolean logNow) {
        int versionCode = getVersionCodeCached(activity);
        boolean logDetail = logNow || SystemClock.elapsedRealtime() - sLastBatchLogTime >= BATCH_LOG_THROTTLE_MS;
        if (logDetail) {
            sLastBatchLogTime = SystemClock.elapsedRealtime();
            Logger.d(TAG, "[ApplyRuleBatch info start------------------------------------]");
        }
        for (ViewRule rule : new ArrayList<>(rules)) {
            if (!rule.enable) {
                continue;
            }
            try {
                if (logDetail) {
                    Logger.d(TAG, "[Apply rule]:" + rule);
                }
                int ruleHashCode = rule.hashCode();
                Pair<WeakReference<View>, ViewProperty> viewInfo = blockedViewCache.get(ruleHashCode);
                View view = viewInfo != null ? viewInfo.first.get() : null;
                if (view == null || !view.isAttachedToWindow()) {
                    blockedViewCache.delete(ruleHashCode);
                    view = ViewFinder.findViewBestMatch(activity, rule, versionCode, logDetail);
                    Preconditions.checkNotNull(view, "apply rule fail not match any view");
                }
                boolean blocked = applyRule(view, rule);
                if (logDetail) {
                    if (blocked) {
                        Logger.i(TAG, String.format("[Success] %s#%s has been blocked", activity, view));
                    } else {
                        Logger.i(TAG, String.format("[Skipped] %s#%s already be blocked", activity, view));
                    }
                }
            } catch (NullPointerException e) {
                if (logDetail) {
                    Logger.w(TAG, String.format("[Failed] %s#%s block failed because %s", activity, rule.viewClass, e.getMessage()));
                }
            }
        }
        if (logDetail) {
            Logger.d(TAG, "[ApplyRuleBatch info end------------------------------------]");
        }
    }

    private static int getNormalParamPxValue(View v, int value) {
        if (value > 0) {
            return DisplayUtils.dp2px(v.getResources(), value);
        }
        return value;
    }

    //    动态使用目标宽高没有改变，是因为在列表中，后面的rule覆盖了前面的rule
    private static int computeTargetWidth(ViewGroup.LayoutParams lp, View v, ViewRule viewRule, ViewProperty viewProperty) {
        int width = viewProperty.layout_params_width;
        if (viewRule.targetParamType == 1 || viewRule.targetParamType == 3) {
            width = getNormalParamPxValue(v, viewRule.targetWidth);
        }
        return width;
    }

    private static int computeTargetHeight(ViewGroup.LayoutParams lp, View v, ViewRule viewRule, ViewProperty viewProperty) {
        int height = viewProperty.layout_params_height;
        if (viewRule.targetParamType == 2 || viewRule.targetParamType == 3) {
            height = getNormalParamPxValue(v, viewRule.targetHeight);
        }
        return height;
    }

    private static boolean applyRuleVisibility(View v, ViewRule viewRule, ViewProperty viewProperty) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        int visibility = viewRule.visibility;
        float alpha = visibility == View.GONE ? 0f : viewRule.getAlphaNormalized();
        int targetWidth = lp == null ? 0 : computeTargetWidth(lp, v, viewRule, viewProperty);
        int targetHeight = lp == null ? 0 : computeTargetHeight(lp, v, viewRule, viewProperty);
        if (v.getVisibility() == visibility
                && Float.compare(v.getAlpha(), alpha) == 0
                && (lp == null || (lp.width == targetWidth && lp.height == targetHeight))) {
            // 已处于目标状态就什么也别动：requestLayout 会再次触发 OnGlobalLayoutListener，
            // 形成每帧重跑 applyRuleBatch 的死循环（102k 次/35min 的实测洪水）。
            return false;
        }
        v.setAlpha(alpha);
        v.setClickable(visibility == View.VISIBLE && viewProperty.clickable);
        if (lp != null) {
            lp.width = targetWidth;
            lp.height = targetHeight;
        }
        v.requestLayout();
        ViewCompat.setVisibility(v, visibility);
        return true;
    }

    public static boolean applyRule(View v, ViewRule viewRule) {
//        if (!viewRule.enable) {
//            return false;
//        }
        int ruleHashCode = viewRule.hashCode();
        Pair<WeakReference<View>, ViewProperty> viewInfo = blockedViewCache.get(ruleHashCode);
        View blockedView = viewInfo != null ? viewInfo.first.get() : null;
        ViewProperty viewProperty;
        if (blockedView == v) {
//            if (v.getVisibility() == viewRule.visibility && v.getVisibility() == View.VISIBLE) {
//                //no change
//                return false;
//            }
            viewProperty = viewInfo.second;
        } else {
            viewProperty = ViewProperty.create(v);
        }
        applyRuleAuto(v, viewRule);
        boolean changed = applyRuleVisibility(v, viewRule, viewProperty);
        if (blockedView != v) {
            blockedViewCache.put(ruleHashCode, Pair.create(new WeakReference<>(v), viewProperty));
        }
//        Logger.d(TAG, String.format(Locale.getDefault(), "apply rule add view cache %d=%s", ruleHashCode, v));
//        Logger.d(TAG, "blockedViewCache:" + blockedViewCache);
        return changed;
    }

    private static final ArrayList<Integer> clickedViews = new ArrayList<>();

    private static void applyRuleAuto(View v, ViewRule viewRule) {
        int code = viewRule.hashCode();
        if (!clickedViews.contains(code)) {
            if (viewRule.autoClick) {
                v.callOnClick();
                clickedViews.add(code);
            }
        }
    }

    public static void revokeRuleBatch(Activity activity, List<ViewRule> rules) {
        int versionCode=getVersionCodeCached(activity);
        for (ViewRule rule : new ArrayList<>(rules)) {
            if (!rule.enable) {
                continue;
            }
            try {
                Logger.d(TAG, "revoke rule:" + rule);
                int ruleHashCode = rule.hashCode();
                Pair<WeakReference<View>, ViewProperty> viewInfo = blockedViewCache.get(ruleHashCode);
                View view = viewInfo != null ? viewInfo.first.get() : null;
                if (view == null || !view.isAttachedToWindow()) {
                    Logger.w(TAG, "view cache not found");
                    blockedViewCache.delete(ruleHashCode);
                    view = ViewFinder.findViewBestMatch(activity, rule, versionCode, true);
                    Logger.w(TAG, "find view in activity" + view);
                    Preconditions.checkNotNull(view, "revoke rule fail can't found block view");
                }
                revokeRule(view, rule);
                Logger.i(TAG, String.format("###revoke rule success [Act]:%s  [View]:%s", activity, view));
            } catch (NullPointerException e) {
                Logger.w(TAG, String.format("###revoke rule fail [Act]:%s  [View]:%s [Reason]:%s", activity, null, e.getMessage()));
            }
        }
    }

    public static void revokeRule(View v, ViewRule viewRule) {
        int ruleHashCode = viewRule.hashCode();
        Pair<WeakReference<View>, ViewProperty> viewInfo = blockedViewCache.get(ruleHashCode);
        if (viewInfo != null && viewInfo.first.get() == v) {
            ViewProperty viewProperty = viewInfo.second;
            v.setAlpha(viewProperty.alpha);
            v.setClickable(viewProperty.clickable);
            ViewCompat.setVisibility(v, viewProperty.visibility);
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null) {
                lp.width = viewProperty.layout_params_width;
                lp.height = viewProperty.layout_params_height;
//                v.setLayoutParams(lp);
                v.requestLayout();
            }
            blockedViewCache.delete(viewRule.hashCode());
            Logger.d(TAG, String.format(Locale.getDefault(), "revoke blocked view %d=%s %s", ruleHashCode, v, viewProperty));
        } else {
            // cache missing why?
            Logger.w(TAG, "view cache missing why?");
            v.setAlpha(viewRule.getAlphaNormalized());
            ViewCompat.setVisibility(v, viewRule.visibility);
        }
    }

    private static final class ViewProperty {

        final float alpha;
        final boolean clickable;
        final int visibility;
        final int layout_params_width;
        final int layout_params_height;

        public ViewProperty(float alpha, boolean clickable, int visibility, int layout_params_width, int layout_params_height) {
            this.alpha = alpha;
            this.clickable = clickable;
            this.visibility = visibility;
            this.layout_params_width = layout_params_width;
            this.layout_params_height = layout_params_height;
        }

        @Override
        public String toString() {
            final StringBuffer sb = new StringBuffer("ViewProperty{");
            sb.append("alpha=").append(alpha);
            sb.append(", clickable=").append(clickable);
            sb.append(", visibility=").append(visibility);
            sb.append(", layout_params_width=").append(layout_params_width);
            sb.append(", layout_params_height=").append(layout_params_height);
            sb.append('}');
            return sb.toString();
        }

        public static ViewProperty create(View view) {
            float alpha = view.getAlpha();
            boolean clickable = view.isClickable();
            int visibility = view.getVisibility();
            ViewGroup.LayoutParams layoutParams = view.getLayoutParams();
            int width = layoutParams != null ? layoutParams.width : 0;
            int height = layoutParams != null ? layoutParams.height : 1;
            return new ViewProperty(alpha, clickable, visibility, width, height);
        }
    }

}
