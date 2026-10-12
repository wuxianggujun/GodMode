package tiiehenry.viewcontroller.injection.bridge;

import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import android.util.Log;

import tiiehenry.viewcontroller.IGodModeManager;
import tiiehenry.viewcontroller.IObserver;
import tiiehenry.viewcontroller.rule.ActRules;
import tiiehenry.viewcontroller.rule.AppRules;
import tiiehenry.viewcontroller.rule.ViewRule;
import com.kaisar.xservicemanager.XServiceManager;

public final class GodModeManager {

    private static final String TAG = "GodModeManager";
    private static final GodModeManager INSTANCE = new GodModeManager();
    private IGodModeManager service;

    private GodModeManager() { }

    public static GodModeManager getDefault() {
        return INSTANCE;
    }

    /** A missing or dead Binder is retried on the next call; rules only live in the module service. */
    private synchronized IGodModeManager requireService() throws RemoteException {
        if (service == null || !service.asBinder().isBinderAlive()) {
            IBinder binder = XServiceManager.getService("godmode");
            if (binder == null || !binder.isBinderAlive()) {
                service = null;
                throw new RemoteException("GodMode Binder service unavailable");
            }
            service = IGodModeManager.Stub.asInterface(binder);
        }
        return service;
    }

    public void setEditMode(boolean enable) {
        try {
            requireService().setEditMode(enable);
        } catch (RemoteException e) {
            Log.w(TAG, "setEditMode failed", e);
        }
    }

    public boolean isInEditMode() {
        try {
            return requireService().isInEditMode();
        } catch (RemoteException e) {
            Log.w(TAG, "isInEditMode failed", e);
            return false;
        }
    }

    public void addObserver(String packageName, IObserver observer) {
        try {
            requireService().addObserver(packageName, observer);
        } catch (RemoteException e) {
            Log.w(TAG, "addObserver failed", e);
        }
    }

    public void removeObserver(String packageName, IObserver observer) {
        try {
            requireService().removeObserver(packageName, observer);
        } catch (RemoteException e) {
            Log.w(TAG, "removeObserver failed", e);
        }
    }

    public AppRules getAllRules() {
        try {
            AppRules rules = requireService().getAllRules();
            return rules != null ? rules : new AppRules();
        } catch (RemoteException e) {
            Log.w(TAG, "getAllRules failed", e);
            return new AppRules();
        }
    }

    public ActRules getRules(String packageName) {
        try {
            ActRules rules = requireService().getRules(packageName);
            return rules != null ? rules : new ActRules();
        } catch (RemoteException e) {
            Log.w(TAG, "getRules failed", e);
            return new ActRules();
        }
    }

    public boolean  writeAllRule(String packageName, ActRules actRules) {
        try {
            return requireService().writeAllRule(packageName,actRules);
        } catch (RemoteException e) {
            Log.w(TAG, "writeAllRule failed", e);
            return false;
        }
    }

    public boolean writeRule(String packageName, ViewRule viewRule, Bitmap bitmap) {
        try {
            return requireService().writeRule(packageName, viewRule, bitmap);
        } catch (RemoteException e) {
            Log.w(TAG, "writeRule failed", e);
            return false;
        }
    }

    public boolean updateRule(String packageName, ViewRule viewRule) {
        try {
            return requireService().updateRule(packageName, viewRule);
        } catch (RemoteException e) {
            Log.w(TAG, "updateRule failed", e);
            return false;
        }
    }

    public boolean deleteRule(String packageName, ViewRule viewRule) {
        try {
            return requireService().deleteRule(packageName, viewRule);
        } catch (RemoteException e) {
            Log.w(TAG, "deleteRule failed", e);
            return false;
        }
    }

    public boolean deleteRules(String packageName) {
        try {
            return requireService().deleteRules(packageName);
        } catch (RemoteException e) {
            Log.w(TAG, "deleteRules failed", e);
            return false;
        }
    }

    public void setAppEnable(String packageName, boolean enable) {
        try {
            requireService().setAppEnable(packageName, enable);
        } catch (RemoteException e) {
            Log.w(TAG, "setAppEnable failed", e);
        }
    }

    public String[] getDisabledApps() {
        try {
            String[] packages = requireService().getDisabledApps();
            return packages != null ? packages : new String[0];
        } catch (RemoteException e) {
            Log.w(TAG, "getDisabledApps failed", e);
            return new String[]{};
        }
    }

    public boolean isAppDisabled(String packageName) {
        try {
            return requireService().isAppDisabled(packageName);
        } catch (RemoteException e) {
            Log.w(TAG, "isAppDisabled failed", e);
            return false;
        }
    }

    public ParcelFileDescriptor openImageFileDescriptor(String filePath) {
        try {
            return requireService().openImageFileDescriptor(filePath);
        } catch (RemoteException e) {
            Log.w(TAG, "openImageFileDescriptor failed", e);
            return null;
        }
    }

    public void writeBitmap(String packageName, String imagePath, Bitmap bitmap) {
        try {
            requireService().writeBitmap(packageName,imagePath,bitmap);
        } catch (RemoteException e) {
            Log.w(TAG, "writeBitmap failed", e);
        }
    }
}
