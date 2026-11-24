package com.dyPlaySpeed.Onely;

/**
 * 模块 App 与 hook 进程共享的配置键约定。
 *
 * group 必须使用模块包名；hook 进程通过
 * XposedInterface.getRemotePreferences(PREFS_GROUP) 只读访问，
 * 模块 App 通过 XposedService.getRemotePreferences(PREFS_GROUP) 写入。
 */
public final class PrefsConstants {

    public static final String PREFS_GROUP = "com.dyPlaySpeed.Onely";
    public static final String KEY_SPEED = "speed";
    public static final float DEFAULT_SPEED = 1.5f;

    private PrefsConstants() {
    }
}
