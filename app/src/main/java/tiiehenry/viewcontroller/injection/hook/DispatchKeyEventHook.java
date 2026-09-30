package tiiehenry.viewcontroller.injection.hook;

import static tiiehenry.viewcontroller.GodModeApplication.TAG;
import static tiiehenry.viewcontroller.injection.util.ViewBitmapUtils.recycleNullableBitmap;

import android.animation.Animator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.appcompat.widget.TooltipCompat;

import tiiehenry.viewcontroller.R;
import tiiehenry.viewcontroller.injection.control.ViewController;
import tiiehenry.viewcontroller.injection.control.ViewExtractor;
import tiiehenry.viewcontroller.injection.control.ViewFinder;
import tiiehenry.viewcontroller.injection.control.ViewHelper;
import tiiehenry.viewcontroller.injection.bridge.GodModeManager;
import tiiehenry.viewcontroller.injection.injector.InjectorImplApps;
import tiiehenry.viewcontroller.injection.util.GmLayoutInflater;
import tiiehenry.viewcontroller.injection.util.GmResources;
import tiiehenry.viewcontroller.injection.util.Logger;
import tiiehenry.viewcontroller.injection.util.Property;
import tiiehenry.viewcontroller.injection.weiget.MaskView;
import tiiehenry.viewcontroller.injection.weiget.ParticleView;
import tiiehenry.viewcontroller.rule.ViewRule;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

public final class DispatchKeyEventHook implements Property.OnPropertyChangeListener<Boolean>, SeekBar.OnSeekBarChangeListener {

    private static final int OVERLAY_COLOR = Color.argb(150, 255, 0, 0);
    private final List<WeakReference<View>> mViewNodes = new ArrayList<>();
    private final InjectorImplApps injectorImplApps;
    private int mCurrentViewIndex = 0;

    private boolean showing = false;
    private MaskView mMaskView;
    private View mNodeSelectorPanel;
    private Activity activity = null;
    private SeekBar seekbar = null;
    public static volatile boolean mKeySelecting = false;
    private EventHandlerHook eventHandlerHook;

    public DispatchKeyEventHook(InjectorImplApps injectorImplApps) {
        this.injectorImplApps = injectorImplApps;
        // 旧实现在 showNodeSelectPanel 里每次都 findAndHookMethod 一次且从不解挂：
        // 挂 N 次后一次音量键会让 seekbar 跳 N 格；面板关掉后残留的 hook 还会对所有按键
        // setResult(true)，back 键之类的物理键全部失效，直到进程死掉。进程级只挂一次。
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchKeyEvent", KeyEvent.class, new XC_MethodHook() {
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!mKeySelecting || !injectorImplApps.editModeProp.get() || eventHandlerHook.isDragging()) {
                    return;
                }
                KeyEvent event = (KeyEvent) param.args[0];
                int action = event.getAction();
                int keyCode = event.getKeyCode();
                if (action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                    seekbarreduce();
                } else if (action == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                    seekbaradd();
                }
                param.setResult(true);
            }
        });
    }

    public void setActivity(final Activity a) {
        reset();
        activity = a;
    }

    private void reset() {
        dismissNodeSelectPanel();
    }

    public void setDisplay(Boolean display) {
        if (display) {
            if (showing)return;
            if (activity == null) return;
            showNodeSelectPanel(activity);
        } else {
            dismissNodeSelectPanel();
        }
    }

    private void showViewDetailDialog(View view) {
        ViewRule viewRule;
        try {
            viewRule = ViewExtractor.makeRule(view);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return;
        }
        eventHandlerHook.hasDialog = true;
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Light_Dialog)
                .setTitle("Attribute")
                .setMessage(viewRule.toString())
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        eventHandlerHook.hasDialog = false;
                    }
                })
                .show();
    }

    private void animateShowNodeSelectorPanel() {
        mNodeSelectorPanel.animate()
                .alpha(1.0f)
                .setInterpolator(new DecelerateInterpolator(1.0f))
                .setDuration(300)
                .start();
    }

    private void showNodeSelectPanel(final Activity activity) {
        /*if (showing)
            return;*/
        showing = true;
        mViewNodes.clear();
        mCurrentViewIndex = 0;
        //build view hierarchy tree
        final ViewGroup container = (ViewGroup) activity.getWindow().getDecorView();
        mViewNodes.addAll(ViewHelper.buildViewNodes(container));
        mMaskView = MaskView.makeMaskView(activity);
        mMaskView.setMaskOverlay(OVERLAY_COLOR);
        try {
            LayoutInflater layoutInflater = GmLayoutInflater.from(activity);
            mNodeSelectorPanel = layoutInflater.inflate(R.layout.layout_node_selector, container, false);
            seekbar = mNodeSelectorPanel.findViewById(R.id.slider);
            seekbar.setMax(mViewNodes.size() - 1);
            seekbar.setOnSeekBarChangeListener(this);
            View btnBlock = mNodeSelectorPanel.findViewById(R.id.block);
            TooltipCompat.setTooltipText(btnBlock, GmResources.getTextSafe(activity, R.string.accessibility_block));
            btnBlock.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final View view = mViewNodes.get(mCurrentViewIndex).get();
                    if (view == null) {
                        return;
                    }
                    showViewDetailDialog(view);
                }
            });
            btnBlock.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    try {
                        mNodeSelectorPanel.setAlpha(0f);
                        final View view = mViewNodes.get(mCurrentViewIndex).get();
                        Logger.d(TAG, "removed view = " + view);
                        if (view != null) {
                            //hide overlay
                            mMaskView.updateOverlayBounds(new Rect());
                            final ViewRule viewRule = ViewExtractor.makeRule(view);
                            // 先落盘再播动画：Compose 一类自绘视图快照可能抛异常、缓存位图也可能拿不到，
                            // boom 一旦没跑起来，原先写在 onAnimationEnd 里的 writeRule 就永远不执行，规则存不下来。
                            Bitmap snapshot = null;
                            try {
                                snapshot = ViewExtractor.snapshotView(ViewFinder.findTopParentViewByChildView(view));
                            } catch (Throwable t) {
                                Logger.w(TAG, "snapshot view fail, rule saved without preview", t);
                            }
                            // 落盘的规则要带最终状态：GONE 在这赋值，onAnimationStart 里只管应用。
                            viewRule.visibility = View.GONE;
                            boolean written = GodModeManager.getDefault().writeRule(activity.getPackageName(), viewRule, snapshot);
                            Logger.d(TAG, "writeRule pkg=" + activity.getPackageName()
                                    + " vis=" + viewRule.visibility + " snapshot=" + (snapshot != null)
                                    + " written=" + written);
                            final Bitmap finalSnapshot = snapshot;
                            final ParticleView particleView = new ParticleView(activity);
                            particleView.setDuration(1000);
                            particleView.attachToContainer(container);
                            particleView.setOnAnimationListener(new ParticleView.OnAnimationListener() {
                                @Override
                                public void onAnimationStart(View animView, Animator animation) {
                                    ViewController.applyRule(view, viewRule);
                                }

                                @Override
                                public void onAnimationEnd(View animView, Animator animation) {
                                    recycleNullableBitmap(finalSnapshot);
                                    particleView.detachFromContainer();
                                    animateShowNodeSelectorPanel();
                                }
                            });
                            particleView.boom(view);
                        }
                        mViewNodes.remove(mCurrentViewIndex--);
                        seekbar.setMax(mViewNodes.size() - 1);
                        return true;
                    } catch (Exception e) {
                        Logger.e(TAG, "block fail", e);
                        animateShowNodeSelectorPanel();
                        Toast.makeText(activity, GmResources.getStringSafe(activity, R.string.block_fail, e.getMessage()), Toast.LENGTH_SHORT).show();
                    }
                    return false;
                }
            });
            View exchange = mNodeSelectorPanel.findViewById(R.id.exchange);
            ViewGroup topcentent = mNodeSelectorPanel.findViewById(R.id.topcentent);
            exchange.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Display display = activity.getWindowManager().getDefaultDisplay();
                    int width = display.getWidth();
                    int padding = topcentent.getPaddingBottom();
                    int targetWidth = width - topcentent.getChildAt(0).getWidth() - padding * 2;
                    if (topcentent.getPaddingRight() >= targetWidth / 2) {
                        topcentent.setPadding(padding, padding, padding, padding);
                    } else {
                        topcentent.setPadding(padding, padding, targetWidth, padding);
                    }
                }
            });
            exchange.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    eventHandlerHook.exchangeEditMode();
                    return true;
                }
            });
            View btnUp = mNodeSelectorPanel.findViewById(R.id.Up);
            btnUp.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    seekbaradd();
                }
            });
            View btnDown = mNodeSelectorPanel.findViewById(R.id.Down);
            btnDown.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    seekbarreduce();
                }
            });
            mMaskView.attachToContainer(container);
            container.addView(mNodeSelectorPanel);
            mNodeSelectorPanel.setAlpha(0);
            mNodeSelectorPanel.post(new Runnable() {
                @Override
                public void run() {
                    mNodeSelectorPanel.setTranslationX(mNodeSelectorPanel.getWidth() / 2.0f);
                    mNodeSelectorPanel.animate()
                            .alpha(1)
                            .translationX(0)
                            .setDuration(300)
                            .setInterpolator(new DecelerateInterpolator(1.0f))
                            .start();
                }
            });
            mKeySelecting = true;
        } catch (Exception e) {
            //god mode package uninstalled?
            Logger.e(TAG, "showNodeSelectPanel fail", e);
            mKeySelecting = false;
            // 充气失败时 mask 已经挂在 DecorView 上了：它是 MATCH_PARENT 且 tag=gm_cmp，
            // 留在上面会永久盖住界面、吞掉所有手势，长按写规则就再也触发不了。摘掉并复位 showing。
            if (mMaskView != null) {
                mMaskView.detachFromContainer();
                mMaskView = null;
            }
            showing = false;
        }
    }

    private void seekbaradd() {
        if (seekbar.getProgress() == seekbar.getMax()) {
            return;
        }
        int Progress = seekbar.getProgress() + 1;
        seekbar.setProgress(Progress);
        onProgressChanged(seekbar, Progress, true);
    }

    private void seekbarreduce() {
        if (seekbar.getProgress() == 0) {
            return;
        }
        int Progress = seekbar.getProgress() - 1;
        seekbar.setProgress(Progress);
        onProgressChanged(seekbar, Progress, true);
    }

    private void dismissNodeSelectPanel() {
        /*if (!showing)
            return;*/
        showing = false;
        if (mMaskView != null) {
            mMaskView.detachFromContainer();
            mMaskView = null;
        }
        if (mNodeSelectorPanel != null) {
            removeNodeSelectorPanel(mNodeSelectorPanel);
        }
        mNodeSelectorPanel = null;
        mViewNodes.clear();
        mCurrentViewIndex = 0;
        mKeySelecting = false;
    }

    private void removeNodeSelectorPanel(View nodeSelectorPanel) {
        nodeSelectorPanel.post(new Runnable() {
            @Override
            public void run() {
                nodeSelectorPanel.animate()
                        .alpha(0)
                        .translationX(nodeSelectorPanel.getWidth() / 2.0f)
                        .setDuration(250)
                        .setInterpolator(new AccelerateInterpolator(1.0f))
                        .withEndAction(new Runnable() {
                            @Override
                            public void run() {
                                ViewGroup parent = (ViewGroup) nodeSelectorPanel.getParent();
                                if (parent != null) parent.removeView(nodeSelectorPanel);
                            }
                        })
                        .start();
            }
        });
    }

    @Override
    public void onPropertyChange(Boolean enable) {
        if (mMaskView != null) {
            dismissNodeSelectPanel();
        }
    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (fromUser) {
            mCurrentViewIndex = progress;
            View view = mViewNodes.get(mCurrentViewIndex).get();
            Logger.d(TAG, String.format(Locale.getDefault(), "progress=%d selected view=%s", progress, view));
            if (view != null) {
                mMaskView.updateOverlayBounds(ViewHelper.getLocationInWindow(view));
            }
        }
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        mNodeSelectorPanel.setAlpha(0.2f);
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        mNodeSelectorPanel.setAlpha(1f);
    }

    public void setEventHandlerHook(EventHandlerHook eventHandlerHook) {
        this.eventHandlerHook = eventHandlerHook;
    }
}
