package tiiehenry.viewcontroller.injection.util;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.Resources.Theme;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import tiiehenry.viewcontroller.BuildConfig;
import tiiehenry.viewcontroller.R;

import java.lang.reflect.InvocationTargetException;

public class GmLayoutInflater implements LayoutInflater.Factory2 {

    public static LayoutInflater from(Context context) throws PackageManager.NameNotFoundException {
        Context gmContext;
        try {
            gmContext = context.createPackageContext(BuildConfig.APPLICATION_ID, 0);
        } catch (PackageManager.NameNotFoundException e) {
            // 沙盒里模块不是已安装的虚拟包，createPackageContext 必失败（原因同 GmResources）。
            // 模块的资源和类都在自己那份 APK 里：套一层 ContextWrapper 把 getResources()/getClassLoader()
            // 指到模块那份，inflater 就能充气模块布局。
            final Context base = context;
            gmContext = new ContextWrapper(base) {
                private Theme mTheme;

                @Override
                public Resources getResources() {
                    return GmResources.getGmResourceSafe(base);
                }

                // 不覆盖 getTheme() 的话 obtainStyledAttributes 走宿主主题，宿主资源表里没有
                // 模块的 0x7fxxxxxx，背景 drawable 立刻 NotFoundException。用模块自己的 AppTheme。
                @Override
                public Theme getTheme() {
                    if (mTheme == null) {
                        Resources res = getResources();
                        if (res == null) {
                            return base.getTheme();
                        }
                        mTheme = res.newTheme();
                        mTheme.applyStyle(R.style.AppTheme, true);
                    }
                    return mTheme;
                }

                @Override
                public ClassLoader getClassLoader() {
                    return GmLayoutInflater.class.getClassLoader();
                }
            };
        }
        // cloneInContext 是必须的：宿主的 inflater 已经装过 factory，直接 setFactory2 会被忽略。
        LayoutInflater layoutInflater = LayoutInflater.from(gmContext).cloneInContext(gmContext);
        GmLayoutInflater factory = new GmLayoutInflater();
        layoutInflater.setFactory2(factory);
        return layoutInflater;
    }

    @Nullable
    @Override
    public View onCreateView(@Nullable View parent, @NonNull String name, @NonNull Context context, @NonNull AttributeSet attrs) {
        // XML 里的简单名（FrameLayout/ImageButton…）解析是 LayoutInflater 前缀表（android.widget. 等）的职责，
        // 这里 forName 只会抛 ClassNotFoundException 刷屏。自定义控件必然以全限定名进来，才需要反射构造。
        if (name.indexOf('.') < 0) {
            return null;
        }
        try {
            return (View) Class.forName(name).getConstructor(Context.class, AttributeSet.class).newInstance(context, attrs);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull String name, @NonNull Context context, @NonNull AttributeSet attrs) {
        if (name.indexOf('.') < 0) {
            return null;
        }
        try {
            return (View) Class.forName(name).getConstructor(Context.class, AttributeSet.class).newInstance(context, attrs);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }
}
