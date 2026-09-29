package tiiehenry.viewcontroller.injection.hook;

import static tiiehenry.viewcontroller.GodModeApplication.TAG;
import static tiiehenry.viewcontroller.injection.control.ViewHelper.TAG_GM_CMP;
import static tiiehenry.viewcontroller.injection.util.ViewBitmapUtils.recycleNullableBitmap;

import android.animation.Animator;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.Toast;

import tiiehenry.viewcontroller.injection.control.ViewController;
import tiiehenry.viewcontroller.injection.control.ViewExtractor;
import tiiehenry.viewcontroller.injection.control.ViewFinder;
import tiiehenry.viewcontroller.injection.control.ViewHelper;
import tiiehenry.viewcontroller.injection.bridge.GodModeManager;
import tiiehenry.viewcontroller.injection.util.Logger;
import tiiehenry.viewcontroller.injection.util.Property;
import tiiehenry.viewcontroller.injection.weiget.CancelView;
import tiiehenry.viewcontroller.injection.weiget.MaskView;
import tiiehenry.viewcontroller.injection.weiget.ParticleView;
import tiiehenry.viewcontroller.rule.ViewRule;
import tiiehenry.viewcontroller.util.Preconditions;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Created by jrsen on 17-12-6.
 */

public final class EventHandlerHook extends XC_MethodHook implements Property.OnPropertyChangeListener<Boolean> {

    private static final int MARK_COLOR = Color.argb(150, 139, 195, 75);

    private boolean mIsInEditMode;
    private int mSkipLogQuota = 3;
    private float mX, mY;
    private Bitmap mSnapshot;
    private ViewRule mViewRule;
    private MaskView mMaskView;
    private CancelView mCancelView;
    private boolean mHasBlockEvent;
    private boolean mLongClick;
    private CheckForLongPress mPendingCheckForLongPress;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private volatile boolean mMultiPointLock;
    private volatile boolean mDragging;

    private final int[] mCancelViewLocation = new int[2];

    @Override
    protected void beforeHookedMethod(MethodHookParam param) {
        if (hasDialog) {
            logSkip("hasDialog");
            return;
        }
        if (!mIsInEditMode) {
            logSkip("not in edit mode");
            return;
        }
        String methodName = param.method.getName();
        if ("dispatchTouchEvent".equals(methodName)) {
            View view = (View) param.thisObject;
            MotionEvent event = (MotionEvent) param.args[0];
            if (!TAG_GM_CMP.equals(view.getTag())) {
                param.setResult(dispatchTouchEvent(view, event));
            }
        }
    }

    private float mDeltaX, mDeltaY;
    public boolean hasDialog = false;

    private boolean dispatchTouchEvent(View v, MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (mMultiPointLock) {
                Toast.makeText(v.getContext(), "不支持多点操作", Toast.LENGTH_SHORT).show();
                return false;
            }
            if (!isAttachedToActivity(v)) {
                if (!mHasBlockEvent) {
                    Toast.makeText(v.getContext(), "该控件属于悬浮窗暂不支持编辑", Toast.LENGTH_SHORT).show();
                    mHasBlockEvent = true;
                }
                return false;
            }
            mDragging = true;
            mMultiPointLock = true;//防止多个触点同时触发
            //防止列表控件拦截事件传递
            ViewParent parent = v.getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(true);
            Rect rect = ViewHelper.getLocationInWindow(v);
            mDeltaX = event.getRawX() - rect.left;
            mDeltaY = event.getRawY() - rect.top;
            mPendingCheckForLongPress = new CheckForLongPress(v);
            mHandler.postDelayed(mPendingCheckForLongPress, ViewConfiguration.getLongPressTimeout());
        } else if (action == MotionEvent.ACTION_MOVE) {
            float x = event.getX();
            float y = event.getY();
            if (mLongClick) {
                mMaskView.updateOverlayBounds((int) (event.getRawX() - this.mDeltaX), (int) (event.getRawY() - this.mDeltaY), v.getWidth(), v.getHeight());
                // 撤销判定看手指落点，不看 mask bounds。原写法用 CancelView 与 mask 的 bounds
                // 求交：Jetpack Compose 的目标 view 是铺满整个 activity 的 ComposeView，mask
                // bounds 恒为全屏，与顶部撤销条必然相交，于是每条规则都被当成「拖到撤销区」丢掉，
                // writeRule 永远不执行。
                // getRealBounds 是 view 坐标系，getRawX/Y 是屏幕坐标系，非全屏窗口下两者差一个
                // 状态栏高度，得加上 CancelView 在屏幕上的偏移再比。
                Rect cancelBounds = mCancelView.getRealBounds();
                mCancelView.getLocationOnScreen(mCancelViewLocation);
                mMaskView.setMarked(cancelBounds.left + mCancelViewLocation[0] <= (int) event.getRawX()
                        && (int) event.getRawX() <= cancelBounds.right + mCancelViewLocation[0]
                        && cancelBounds.top + mCancelViewLocation[1] <= (int) event.getRawY()
                        && (int) event.getRawY() <= cancelBounds.bottom + mCancelViewLocation[1]);
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            Logger.d(TAG, "touch up action=" + action + " view=" + v.getClass().getName()
                    + " longClick=" + mLongClick + " marked=" + (mMaskView != null && mMaskView.isMarked())
                    + " hasRule=" + (mViewRule != null));
            ViewParent parent = v.getParent();
            if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
            mHandler.removeCallbacks(mPendingCheckForLongPress);
            if (mLongClick) {
                performDetachMirrorView(v);
                mLongClick = false;
            }
            mHasBlockEvent = false;
            mMultiPointLock = false;
            mDragging = false;
        }
        return true;
    }

    private boolean isAttachedToActivity(View v) {
        Object viewRootImpl = ViewFinder.findViewRootImplByChildView(v.getParent());
        if (viewRootImpl == null) return false;
        WindowManager.LayoutParams mWindowAttributes = (WindowManager.LayoutParams) XposedHelpers.getObjectField(viewRootImpl, "mWindowAttributes");
        return mWindowAttributes != null && mWindowAttributes.type == WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
    }

    private void performAttachMirrorView(View v) {
        try {
            //Create mirror view and attach top view hierarchy
            Activity activity = Preconditions.checkNotNull(ViewExtractor.getAttachedActivityFromView(v));

            ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();

            mCancelView = new CancelView(activity);
            mCancelView.attachToContainer(container);

            mMaskView = MaskView.makeMaskView(activity);
            mMaskView.setMaskOverlay(v);
            mMaskView.setMarkColor(MARK_COLOR);
            mMaskView.updateOverlayBounds(ViewHelper.getLocationInWindow(v));
            mMaskView.attachToContainer(container);

            mSnapshot = ViewExtractor.snapshotView(ViewFinder.findTopParentViewByChildView(v));
            mViewRule = ViewExtractor.makeRule(v);
            ViewController.applyRule(v, mViewRule);
            Logger.d(TAG, "attach mirror done rule=" + mViewRule);
        } catch (Throwable e) {
            // 这里挂在 guest 主线程上，任何异常都会把宿主进程拖死，整个应用直接没了。
            // 原来只 catch 两个异常类型，GmResources 抛 NotFoundException 时直接崩。
            Logger.e(TAG, "attach mirror view fail", e);
        }
    }

    private void performDetachMirrorView(final View v) {
        Activity activity = ViewExtractor.getAttachedActivityFromView(v);
        try {
            Preconditions.checkNotNull(activity);
        } catch (NullPointerException e) {
            return;
        }
        Logger.d(TAG, "detach mirror view marked=" + (mMaskView != null && mMaskView.isMarked())
                + " hasRule=" + (mViewRule != null) + " hasSnapshot=" + (mSnapshot != null));
        if (mCancelView != null) {
            mCancelView.detachFromContainer();
        }
        if (mMaskView.isMarked()) {
            //丢弃该条规则
            try {
                mMaskView.detachFromContainer();
                if (mViewRule != null) {
                    mViewRule.visibility = View.VISIBLE;
                }
                ViewController.revokeRule(v, mViewRule);
                recycleNullableBitmap(mSnapshot);
            } finally {
                mSnapshot = null;
                mMaskView = null;
                mCancelView = null;
                mViewRule = null;
            }
        } else {
            ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();
            final ParticleView particleView = new ParticleView(activity);
            particleView.setDuration(1000);
            particleView.attachToContainer(container);
            particleView.setOnAnimationListener(new ParticleView.OnAnimationListener() {
                @Override
                public void onAnimationStart(View animView, Animator animation) {
                    //Make original view gone

                    if (mViewRule != null) {
                        mViewRule.visibility = View.GONE;
                        ViewController.applyRule(v, mViewRule);
                        boolean written = GodModeManager.getDefault().writeRule(v.getContext().getPackageName(), mViewRule, mSnapshot);
                        Logger.d(TAG, "writeRule pkg=" + v.getContext().getPackageName()
                                + " rule=" + mViewRule + " written=" + written);
                    } else {
                        Logger.d(TAG, "writeRule skipped: mViewRule null");
                    }
                    recycleNullableBitmap(mSnapshot);
                    mMaskView.detachFromContainer();
                }

                @Override
                public void onAnimationEnd(View animView, Animator animation) {
                    try {
                        particleView.detachFromContainer();
                    } finally {
                        mSnapshot = null;
                        mMaskView = null;
                        mCancelView = null;
                        mViewRule = null;
                    }
                }
            });
            particleView.boom(mMaskView);
        }
    }

    public void exchangeEditMode() {
        onPropertyChange(!mIsInEditMode);
    }

    // 编辑开关没开时，hook 进来了但什么都不做，外部完全看不出。前几次摸到手指时报一下原因，
    // editMode/hasDialog 哪个把事件挡住了，一眼就能定位。配额用完就闭嘴，不和 GPS 探针一样刷屏。
    private void logSkip(String reason) {
        if (mSkipLogQuota > 0) {
            mSkipLogQuota--;
            Logger.d(TAG, "touch skipped reason=" + reason + " editMode=" + mIsInEditMode);
        }
    }

    @Override
    public void onPropertyChange(Boolean enable) {
        mIsInEditMode = enable;
    }

    private class CheckForLongPress implements Runnable {

        private final WeakReference<View> viewRef;

        private CheckForLongPress(View view) {
            this.viewRef = new WeakReference<>(view);
        }

        @Override
        public void run() {
            View view = viewRef.get();
            Logger.d(TAG, "view =" + view);
            if (view != null) {
                Logger.d(TAG, "perform attach mirror view");
                performAttachMirrorView(view);
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                mLongClick = true;
            }
        }
    }
}
