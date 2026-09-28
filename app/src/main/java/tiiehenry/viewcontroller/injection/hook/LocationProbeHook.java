package tiiehenry.viewcontroller.injection.hook;

import android.app.Activity;
import android.location.Location;
import android.location.LocationListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import static tiiehenry.viewcontroller.GodModeApplication.TAG;
import tiiehenry.viewcontroller.injection.util.Logger;

/**
 * 容器 GPS 伪装的探针：把应用真正拿到的 Location 打到 logcat。
 *
 * 用途是核对 VLocation -> Location 这条链路—— {@link com.lody.virtual.remote.vloc.VLocation}
 * 的六个边界点现在都拦 isEmpty()，但拦不拦得住只有"应用实际收到了什么"能证明。
 * 重点看三件事：坐标是不是 NaN（Parcel 脏数据的典型症状）、范围有没有越界、
 * 系统的 mock 标记清没清掉。
 *
 * 整个探针只在 log.tag.GodMode=DEBUG 时才挂载，正式环境一行都不跑。
 * logcat 里抓 "GodMode" tag 下的 "LocProbe" 即可。
 */
public final class LocationProbeHook {

    private static final String SUB_TAG = "LocProbe";

    // 推送链的刺激点：注册一个 listener，逼应用进程跑一遍 requestLocationUpdates -> 容器 -> 监听器。
    // 挑 (String, long, float, LocationListener) 这个全 SDK 都有的公共重载，框架内部自己造
    // LocationRequest，应用代码完全不用碰隐藏类。
    private static final LocationListener mListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            logLocation("delivered", location);
        }

        @Override
        public void onProviderDisabled(String provider) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };

    private final AtomicBoolean mProbed = new AtomicBoolean();
    public void register(ClassLoader classLoader) {
        if (!Log.isLoggable(TAG, Log.DEBUG)) {
            return;
        }
        XC_MethodHook logLastKnown = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                logLocation("getLastKnownLocation(" + param.args[0] + ")", (Location) param.getResult());
            }
        };
        try {
            XposedHelpers.findAndHookMethod("android.location.LocationManager", classLoader,
                    "getLastKnownLocation", String.class, logLastKnown);
        } catch (Throwable e) {
            Logger.w(TAG, SUB_TAG + " hook getLastKnownLocation fail", e);
        }

        // requestLocationUpdates 的交付路径：容器是通过 transport 把假坐标送进来的。
        // SDK 34 把 ListenerTransport 改名 LocationListenerTransport，交付方法也变成了
        // onLocationChanged(List<Location>, IRemoteCallback)，按固定签名挂就是 NoSuchMethodError。
        // 这里按名字找到方法直接 hook，两种形态都吃。
        String[] transportNames = {"android.location.LocationManager$LocationListenerTransport",
                "android.location.LocationManager$ListenerTransport"};
        for (String transportName : transportNames) {
            Class<?> clazz;
            try {
                clazz = Class.forName(transportName, false, classLoader);
            } catch (Throwable ignored) {
                continue;
            }
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                if (!"onLocationChanged".equals(m.getName())) {
                    continue;
                }
                try {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Object arg = param.args[0];
                            Location location = null;
                            if (arg instanceof Location) {
                                location = (Location) arg;
                            } else if (arg instanceof java.util.List) {
                                java.util.List<?> list = (java.util.List<?>) arg;
                                if (!list.isEmpty()) {
                                    location = (Location) list.get(list.size() - 1);
                                }
                            }
                            logLocation("onLocationChanged", location);
                        }
                    });
                    Logger.d(TAG, SUB_TAG + " hooked " + m);
                } catch (Throwable e) {
                    Logger.w(TAG, SUB_TAG + " hook " + m + " fail", e);
                }
            }
        }

        // 应用不主动查定位时也得有数据，第一个 Activity 起来后主动问一次
        XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                probeOnce((Activity) param.thisObject);
            }
        });
    }

    private void probeOnce(final Activity activity) {
        if (!mProbed.compareAndSet(false, true) || activity == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    Object lm = activity.getSystemService("location");
                    if (lm == null) {
                        Logger.d(TAG, SUB_TAG + " LocationManager unavailable");
                        return;
                    }
                    Object result = XposedHelpers.callMethod(lm, "getLastKnownLocation", "gps");
                    logLocation("probe(gps)", (Location) result);
                    forceRequestUpdates(lm);
                } catch (Throwable e) {
                    Logger.w(TAG, SUB_TAG + " active probe fail", e);
                }
            }
        });
    }

    private static void logLocation(String source, Location location) {
        if (location == null) {
            Logger.d(TAG, SUB_TAG + " " + source + " -> null");
            return;
        }
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        StringBuilder sb = new StringBuilder(96)
                .append(source).append(" -> ")
                .append(location.getProvider())
                .append(" lat=").append(lat)
                .append(" lon=").append(lon)
                .append(" acc=").append(location.getAccuracy())
                .append(" alt=").append(location.getAltitude())
                .append(" spd=").append(location.getSpeed())
                .append(" brg=").append(location.getBearing());
        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            sb.append(" !!!NaN!!!");
        } else if (Math.abs(lat) > 90.0 || Math.abs(lon) > 180.0) {
            sb.append(" !!!OUT_OF_RANGE!!!");
        }
        sb.append(" ").append(mockFlag(location));
        Logger.d(TAG, sb.toString());
    }

    // 应用不主动 requestLocationUpdates 时，这条 应用 -> 容器 -> 监听器 的推送链验证不到。
    // 用应用自己的 LocationManager 注册一次，把整条链跑通。只挑参数全是 String/基本类型、
    // listener 在末尾的公共重载，任何 SDK 都有，不用构造隐藏的 LocationRequest。
    private static void forceRequestUpdates(Object lm) {
        Method pick = null;
        try {
            for (Method m : lm.getClass().getDeclaredMethods()) {
                if (!"requestLocationUpdates".equals(m.getName())) {
                    continue;
                }
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 0 || !LocationListener.class.isAssignableFrom(p[p.length - 1])) {
                    continue;
                }
                boolean simple = true;
                for (int i = 0; i < p.length - 1; i++) {
                    if (!(p[i] == String.class || p[i] == long.class || p[i] == float.class
                            || p[i] == int.class || p[i] == Looper.class)) {
                        simple = false;
                        break;
                    }
                }
                if (simple) {
                    pick = m;
                    break;
                }
            }
        } catch (Throwable e) {
            Logger.w(TAG, SUB_TAG + " enumerate requestLocationUpdates fail", e);
            return;
        }
        if (pick == null) {
            Logger.w(TAG, SUB_TAG + " no simple requestLocationUpdates overload");
            return;
        }
        Class<?>[] p = pick.getParameterTypes();
        Object[] args = new Object[p.length];
        for (int i = 0; i < p.length; i++) {
            if (p[i] == String.class) {
                args[i] = "gps";
            } else if (p[i] == long.class) {
                args[i] = 0L;
            } else if (p[i] == float.class) {
                args[i] = 0f;
            } else if (p[i] == int.class) {
                args[i] = 0;
            } else if (p[i] == Looper.class) {
                args[i] = Looper.getMainLooper();
            } else if (LocationListener.class.isAssignableFrom(p[i])) {
                args[i] = mListener;
            }
        }
        try {
            pick.setAccessible(true);
            pick.invoke(lm, args);
            Logger.d(TAG, SUB_TAG + " forced " + pick.toGenericString());
        } catch (Throwable e) {
            Logger.w(TAG, SUB_TAG + " force requestLocationUpdates fail", e);
        }
    }
    // API 31 把 isFromMockProvider 换成了 isMock，两个都是隐藏/废弃 API，反射调最稳
    private static String mockFlag(Location location) {
        try {
            return "isFromMockProvider=" + XposedHelpers.callMethod(location, "isFromMockProvider");
        } catch (Throwable ignored) {
            try {
                return "isMock=" + XposedHelpers.callMethod(location, "isMock");
            } catch (Throwable ignored2) {
                return "mockFlag=n/a";
            }
        }
    }
}
