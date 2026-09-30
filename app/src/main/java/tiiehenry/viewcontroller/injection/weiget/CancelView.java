package tiiehenry.viewcontroller.injection.weiget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import tiiehenry.viewcontroller.R;
import tiiehenry.viewcontroller.injection.util.GmResources;

import static tiiehenry.viewcontroller.injection.control.ViewHelper.TAG_GM_CMP;

/**
 * Created by jrsen on 17-11-4.
 */

@SuppressLint("AppCompatCustomView")
public final class CancelView extends View {

    private final Paint rectPaint = new Paint();
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private CharSequence text;
    private final Rect statusBarBounds = new Rect();
    private final Rect textLayoutBounds = new Rect();
    private final Rect textBounds = new Rect();
    private int actionBarHeight;

    public CancelView(Context context) {
        this(context, null);
    }

    public CancelView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CancelView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public CancelView(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        setTag(TAG_GM_CMP);
        initWidget(context);
    }

    private void initWidget(Context context) {
        text = GmResources.getTextSafe(context, R.string.top_revert_tip);
        rectPaint.setStyle(Paint.Style.FILL);
        rectPaint.setColor(Color.argb(230, 139, 195, 75));
        textPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 15f, getResources().getDisplayMetrics()));
        textPaint.setColor(Color.WHITE);
        textPaint.getTextBounds(text.toString(), 0, text.length(), textBounds);
        textBounds.offsetTo(0, 0);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
        setLayoutParams(lp);
        // 撤销条的高度依赖主题的 actionBarSize，但 Theme.DeviceDefault 一类主题不声明它，
        // resolveAttribute 返回 false 时旧代码让 textLayoutBounds 永远保持 (0,0,0,0)——
        // 绿条退化成一条 8px 的状态栏细线，marked 判定区跟着塌缩，用户压根拖不进去，
        // 松手只会走写规则分支。这里在构造期一次解析，解不出来回退 48dp 保底。
        actionBarHeight = resolveActionBarHeight(context);
    }

    private static int resolveActionBarHeight(Context context) {
        TypedValue tv = new TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
            return TypedValue.complexToDimensionPixelSize(tv.data, context.getResources().getDisplayMetrics());
        }
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 48f,
                context.getResources().getDisplayMetrics());
    }

    public void attachToContainer(ViewGroup container) {
        container.addView(this);
    }

    public void detachFromContainer() {
        ViewGroup parent = (ViewGroup) getParent();
        parent.removeView(this);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.save();
        ensureBounds();
        //draw status bar rect
        canvas.drawRect(statusBarBounds, rectPaint);
        canvas.drawRect(textLayoutBounds, rectPaint);

        //draw text
        float x = textLayoutBounds.centerX() - textBounds.centerX();
        float y = textLayoutBounds.centerY() + textBounds.centerY();
        canvas.drawText(text, 0, text.length(), x, y, textPaint);
        canvas.restore();
    }

    /**
     * 计算撤销条的区域。原实现把这段初始化放在 onDraw 里且getRealBounds 直接读字段，
     * attach 之后第一帧还没绘制时命中区是 (0,0,0,0)，手势判定恒为 false。
     * 提到外部并在 getRealBounds 前也调一次，保证查到的是算过的值。
     */
    private void ensureBounds() {
        if (!statusBarBounds.isEmpty() && !textLayoutBounds.isEmpty()) {
            return;
        }
        int right = getWidth() > 0 ? getWidth()
                : getContext().getResources().getDisplayMetrics().widthPixels;
        int statusBarHeight = getStatusBarHeight();
        statusBarBounds.set(0, 0, right, statusBarHeight);
        textLayoutBounds.set(0, statusBarHeight, right, statusBarHeight + actionBarHeight);
    }

    private int getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return getResources().getDimensionPixelSize(resourceId);
        }
        return 0;
    }

    public Rect getRealBounds() {
        ensureBounds();
        return new Rect(statusBarBounds.left, statusBarBounds.top, statusBarBounds.right, textLayoutBounds.bottom);
    }
}
