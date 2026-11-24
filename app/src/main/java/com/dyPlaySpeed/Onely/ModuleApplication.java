package com.dyPlaySpeed.Onely;

import android.app.Application;
import android.content.SharedPreferences;
import android.util.Log;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * 模块 App 侧：注册 libxposed service 监听，把配置暴露为 RemotePreferences。
 *
 * XposedServiceHelper 会缓存框架提前推送的 binder，因此注册早晚都可以；
 * registerListener 只能调用一次，这里用静态标记保证。
 */
public final class ModuleApplication extends Application {

    private static final String TAG = "DYSpeed";
    private static volatile SharedPreferences remotePrefs;
    private static volatile boolean registered = false;

    @Override
    public void onCreate() {
        super.onCreate();
        register();
    }

    public static void register() {
        if (registered) return;
        synchronized (ModuleApplication.class) {
            if (registered) return;
            registered = true;
        }
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override
            public void onServiceBind(XposedService service) {
                try {
                    remotePrefs = service.getRemotePreferences(PrefsConstants.PREFS_GROUP);
                    Log.i(TAG, "Xposed service 已连接，RemotePreferences 可用");
                } catch (Throwable t) {
                    Log.e(TAG, "获取 RemotePreferences 失败", t);
                }
            }

            @Override
            public void onServiceDied(XposedService service) {
                remotePrefs = null;
                Log.w(TAG, "Xposed service 已断开");
            }
        });
    }

    /** 未连接时返回 null，UI 应提示用户确认模块已激活并打开过模块 App。 */
    public static SharedPreferences getRemotePreferences() {
        register();
        return remotePrefs;
    }
}
