package com.dyPlaySpeed.Onely;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam;
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;

/**
 * 抖音播放倍速 - 主 Hook 类（libxposed API 102）
 *
 * 注意：API 102 禁止调用 de.robv.android.xposed.* 遗留 API，
 * 所有 hook / 配置读取 / 日志均使用 XposedModule 提供的现代接口。
 *
 * @author MarsGao
 */
public class MainHook extends XposedModule {

    private static final String TAG = "DYSpeed";
    private static final long RETRY_DELAY_MS = 3000L;
    private static final long SPEED_FORCE_INTERVAL_MS = 2000L;
    private static final int MAX_PLAYER_FAILURES = 5;
    private static final String CAPTURED_PREFS = "dy_speed_follow";
    /** 升级后清理一次旧版误捕获的 1.0，避免它一直压住模块配置 */
    private static final String CAPTURED_FIX_MARKER = "captured_reset_fix_v1";

    public static final String HOOK_PACKAGE_DY0 = "com.ss.android.ugc.aweme";
    public static final String HOOK_PACKAGE_DY1 = "com.ss.android.ugc.aweme.lite";
    public static final String HOOK_PACKAGE_DY2 = "com.ss.android.ugc.live";
    public static final String HOOK_PACKAGE_DY3 = "com.ss.android.ugc.aweme.mobile";

    private static final AtomicInteger HOOK_SEQ = new AtomicInteger(0);
    private static final ArrayList<Object> discoveredPlayers = new ArrayList<>();
    /** 连续调用失败次数，避免切视频后一次失败就把播放器永久丢弃 */
    private static final IdentityHashMap<Object, Integer> playerFailures = new IdentityHashMap<>();

    private volatile String processName = "";
    private volatile boolean strategy1Hooked = false;
    private volatile boolean strategy2Hooked = false;
    private volatile boolean strategy3Hooked = false;
    private volatile boolean strategy4Hooked = false;
    private volatile boolean loopRunning = false;

    /** hook 进程内只读，来自模块 App 通过 libxposed service 写入的 RemotePreferences */
    private SharedPreferences modulePrefs;
    private static Application application;

    /**
     * 防止策略5（hook SharedPreferencesImpl.getFloat）与配置读取互相递归。
     * 旧版 XSharedPreferences 直接读文件，不经过 getFloat；API 102 改用
     * getSharedPreferences 后会重入同一个 hook，导致 native 栈溢出闪退。
     */
    private static final ThreadLocal<Boolean> readingConfig = new ThreadLocal<>();

    // ==================== 生命周期 ====================

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        processName = param.getProcessName();
        try {
            modulePrefs = getRemotePreferences(PrefsConstants.PREFS_GROUP);
            logi("模块已加载，进程=" + processName);
        } catch (Throwable t) {
            loge("RemotePreferences 不可用（使用默认倍速）: " + t);
        }
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        try {
            String pkg = param.getPackageName();
            if (!isDouyinPackage(pkg)) return;
            // 只处理主进程，避免子进程重复挂载
            if (!pkg.equals(processName)) return;

            // 清理升级前残留的误捕获倍速（抖音切视频重置为1.0时被记成“用户设置”）
            clearStaleCapturedOnce();

            ClassLoader cl = param.getClassLoader();
            safeHook(() -> hookSimPlayer(cl));
            safeHook(() -> hookGeneralScan(cl));
            safeHook(this::hookMediaPlayer);
            safeHook(() -> hookActivity(cl));
            safeHook(this::hookPrefsRead);

            scheduleRetryAndForce(cl);
        } catch (Throwable t) {
            loge("onPackageReady: " + t);
        }
    }

    /**
     * 本模块包含后台线程和定时循环，无法保证旧代码状态可安全交接，
     * 因此拒绝热重载（需要热重载时应重启目标应用）。
     */
    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        logi("拒绝热重载：模块含后台定时循环");
        return false;
    }

    private boolean isDouyinPackage(String pkg) {
        return HOOK_PACKAGE_DY0.equals(pkg) || HOOK_PACKAGE_DY1.equals(pkg)
                || HOOK_PACKAGE_DY2.equals(pkg) || HOOK_PACKAGE_DY3.equals(pkg);
    }

    /** 任何被识别的播放器都登记进来，切视频后新播放器也能被后续循环覆盖 */
    private static void registerPlayer(Object player) {
        if (player == null) return;
        synchronized (discoveredPlayers) {
            if (!discoveredPlayers.contains(player)) {
                // 防止长时间刷视频导致播放器对象无限堆积
                if (discoveredPlayers.size() >= 32) {
                    Object oldest = discoveredPlayers.remove(0);
                    playerFailures.remove(oldest);
                }
                discoveredPlayers.add(player);
                playerFailures.remove(player);
            }
        }
    }

    private XposedInterface.HookHandle addHook(Method method, XposedInterface.Hooker hooker) {
        return hook(method)
                .setId("dyspeed." + HOOK_SEQ.incrementAndGet())
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(hooker);
    }

    private void safeHook(Runnable hook) {
        try {
            hook.run();
        } catch (Throwable t) {
            loge("hook 注册失败: " + t);
        }
    }

    // ==================== 延迟重试 + 强制倍速 ====================

    private void scheduleRetryAndForce(final ClassLoader cl) {
        new Thread(() -> {
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                return;
            }
            if (!strategy1Hooked) safeHook(() -> hookSimPlayer(cl));
            if (!strategy2Hooked) safeHook(() -> hookGeneralScan(cl));
            if (!strategy3Hooked) safeHook(this::hookMediaPlayer);
            if (!strategy4Hooked) safeHook(() -> hookActivity(cl));
            safeHook(this::hookPrefsRead);
            startForceLoop();
        }, "dyspeed-init").start();
    }

    // ==================== 策略1: SimPlayer ====================

    private void hookSimPlayer(ClassLoader cl) {
        if (strategy1Hooked) return;
        try {
            Class<?> clazz = cl.loadClass(
                    "com.ss.android.ugc.aweme.video.simplayer.SimPlayer");

            final String[] speedMethods = {
                    "setSpeed", "setPlaySpeed", "setPlaybackSpeed", "setSpeedInMultiplier"
            };

            // 1) 倍速方法：拦截参数并捕获抖音自身设置
            for (Method m : clazz.getDeclaredMethods()) {
                if (Modifier.isAbstract(m.getModifiers())) continue;
                if (!isOneOf(m.getName(), speedMethods)) continue;

                addHook(m, chain -> {
                    Object[] newArgs = null;
                    try {
                        List<Object> args = chain.getArgs();
                        if (!args.isEmpty() && args.get(0) instanceof Number) {
                            float cur = ((Number) args.get(0)).floatValue();
                            float target = getSpeedConfig();
                            // 捕获：抖音设置的倍速拥有最高优先级（但忽略切视频时的非手动 1.0 重置）
                            if (Math.abs(cur - target) > 0.01f && shouldCapture(cur)) {
                                saveCapturedSpeed(cur);
                                target = cur;
                            }
                            if (Math.abs(cur - target) > 0.01f && !isManual()) {
                                // getArgs() 返回的 List 不可变，必须用 proceed(Object[]) 传新参数
                                newArgs = args.toArray();
                                newArgs[0] = target;
                                logi("拦截 " + cur + "->" + target + "x");
                            }
                        }
                    } catch (Throwable t) {
                        loge("策略1 前置: " + t);
                    }

                    Object result = newArgs != null ? chain.proceed(newArgs) : chain.proceed();
                    registerPlayer(chain.getThisObject());
                    try {
                        float target = getSpeedConfig();
                        if (Math.abs(target - 1.0f) > 0.01f) {
                            scanInternal(chain.getThisObject(), target);
                        }
                    } catch (Throwable t) { /* 静默 */ }
                    return result;
                });
            }

            // 2) 其余非抽象方法：调用后补齐倍速（保持原有行为）
            for (Method m : clazz.getDeclaredMethods()) {
                String mn = m.getName();
                if (mn.startsWith("access$") || mn.startsWith("get")
                        || mn.equals("toString") || mn.equals("hashCode")
                        || mn.equals("equals") || mn.equals("clone")
                        || Modifier.isAbstract(m.getModifiers())) continue;
                if (isOneOf(mn, speedMethods)) continue;

                addHook(m, chain -> {
                    Object result = chain.proceed();
                    try {
                        float target = getSpeedConfig();
                        if (Math.abs(target - 1.0f) > 0.01f) {
                            callMethodQuiet(chain.getThisObject(), "setSpeed", target);
                        }
                    } catch (Throwable t) { /* 静默 */ }
                    return result;
                });
            }

            strategy1Hooked = true;
            logi("策略1 SimPlayer 已挂载");
        } catch (Throwable t) {
            loge("策略1 挂载失败: " + t);
        }
    }

    private static boolean isOneOf(String name, String[] candidates) {
        for (String c : candidates) {
            if (c.equals(name)) return true;
        }
        return false;
    }

    // ==================== SimPlayer 内部扫描 ====================

    private void scanInternal(Object obj, float speed) {
        if (obj == null) return;
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        recursiveScan(obj, speed, visited, 0);
    }

    private void recursiveScan(Object obj, float speed,
                               IdentityHashMap<Object, Boolean> visited, int depth) {
        if (obj == null || depth > 4 || visited.containsKey(obj)) return;
        visited.put(obj, Boolean.TRUE);

        String[] methods = {"setSpeed", "setPlaySpeed", "setPlaybackSpeed"};
        Class<?> clz = obj.getClass();
        String cn = clz.getName();
        if (cn.startsWith("java.") || cn.startsWith("android.")) return;

        int d = 0;
        while (clz != null && clz != Object.class && d < 6) {
            for (Field f : clz.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v == null) continue;
                    String fn = f.getName().toLowerCase();
                    String vcn = v.getClass().getName();
                    if (!fn.contains("player") && !fn.contains("delegate") && !fn.contains("inner")
                            && !fn.contains("impl") && !fn.contains("engine") && !fn.contains("session")
                            && !vcn.contains("Player") && !vcn.contains("Session")) continue;

                    for (String mn : methods) {
                        callMethodQuiet(v, mn, speed);
                        registerPlayer(v);
                    }
                    recursiveScan(v, speed, visited, depth + 1);
                } catch (Throwable t) { /* 跳过 */ }
            }
            clz = clz.getSuperclass();
            d++;
        }
    }

    // ==================== 策略2: 通用扫描 ====================

    private void hookGeneralScan(ClassLoader cl) {
        if (strategy2Hooked) return;
        String[][] candidates = {
                {"com.ss.android.ugc.aweme.video.simplayer", "SimPlayer"},
                {"com.ss.android.ugc.aweme.player", "AbsPlayer"},
                {"com.ss.android.ugc.aweme.player", "Player"},
                {"com.bytedance.playerkit.player", "Player"},
                {"com.bytedance.playerkit.player", "IMediaPlayer"},
                {"com.bytedance.android.horizon.player", "HorizonPlayerController"},
                {"com.ss.android.ugc.aweme.feed.controller", "FeedPlayerWrapper"},
                {"com.ss.android.ugc.aweme.horizon.horizonplayer", "DHorizonPlayerImpl"},
        };
        String[] speedMethods = {"setSpeed", "setPlaySpeed", "setPlaybackSpeed"};
        int hooked = 0;
        for (String[] pkgCls : candidates) {
            try {
                Class<?> clazz = cl.loadClass(pkgCls[0] + "." + pkgCls[1]);
                for (String mn : speedMethods) {
                    final Method m = findMethod(clazz, mn, float.class);
                    if (m == null) continue;

                    addHook(m, chain -> {
                        Object[] newArgs = null;
                        try {
                            List<Object> args = chain.getArgs();
                            if (!args.isEmpty() && args.get(0) instanceof Number) {
                                float cur = ((Number) args.get(0)).floatValue();
                                float target = getSpeedConfig();
                                if (Math.abs(cur - target) > 0.01f && shouldCapture(cur)) {
                                    saveCapturedSpeed(cur);
                                    target = cur;
                                }
                                if (Math.abs(cur - target) > 0.01f && !isManual()) {
                                    newArgs = args.toArray();
                                    newArgs[0] = target;
                                    logi("策略2 拦截 " + cur + "->" + target + "x");
                                }
                            }
                        } catch (Throwable t) { /* 静默 */ }
                        Object result = newArgs != null ? chain.proceed(newArgs) : chain.proceed();
                        registerPlayer(chain.getThisObject());
                        return result;
                    });
                    hooked++;
                }
            } catch (Throwable t) { /* 忽略 */ }
        }
        if (hooked > 0) {
            strategy2Hooked = true;
            logi("策略2 通用扫描 已挂载 " + hooked + " 个方法");
        }
    }

    // ==================== 策略3: MediaPlayer ====================

    private void hookMediaPlayer() {
        if (strategy3Hooked) return;
        try {
            Method m = MediaPlayer.class.getDeclaredMethod(
                    "setPlaybackParams", PlaybackParams.class);
            addHook(m, chain -> {
                Object[] newArgs = null;
                try {
                    List<Object> args = chain.getArgs();
                    PlaybackParams params = (PlaybackParams) args.get(0);
                    float cur = params.getSpeed();
                    float target = getSpeedConfig();
                    if (Math.abs(cur - target) > 0.01f && shouldCapture(cur)) {
                        saveCapturedSpeed(cur);
                        target = cur;
                    }
                    if (Math.abs(cur - 1.0f) < 0.01f
                            && Math.abs(target - 1.0f) > 0.01f && !isManual()) {
                        params.setSpeed(target);
                        newArgs = args.toArray();
                        newArgs[0] = params;
                    }
                } catch (Throwable t) { /* 静默 */ }
                Object result = newArgs != null ? chain.proceed(newArgs) : chain.proceed();
                registerPlayer(chain.getThisObject());
                return result;
            });
            strategy3Hooked = true;
            logi("策略3 MediaPlayer 已挂载");
        } catch (Throwable t) {
            loge("策略3 挂载失败: " + t);
        }
    }

    // ==================== 策略4: Activity 反射 ====================

    private void hookActivity(ClassLoader cl) {
        if (strategy4Hooked) return;
        try {
            Class<?> activityClass = cl.loadClass("android.app.Activity");
            Method onResume = findMethod(activityClass, "onResume");
            if (onResume == null) return;

            addHook(onResume, chain -> {
                Object result = chain.proceed();
                try {
                    Object self = chain.getThisObject();
                    String name = self == null ? "" : self.getClass().getName();
                    if (!name.contains("video") && !name.contains("Video")
                            && !name.contains("feed") && !name.contains("Feed")
                            && !name.contains("aweme") && !name.contains("Aweme")) return result;
                    new Handler(Looper.getMainLooper())
                            .postDelayed(() -> scanActivity(self), 800);
                } catch (Throwable t) { /* 静默 */ }
                return result;
            });
            strategy4Hooked = true;
            logi("策略4 Activity 反射 已挂载");
        } catch (Throwable t) {
            loge("策略4 挂载失败: " + t);
        }
    }

    private void scanActivity(Object activity) {
        float speed = getSpeedConfig();
        Class<?> clz = activity.getClass();
        int depth = 0;
        while (clz != null && clz != Object.class && depth < 8) {
            for (Field f : clz.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(activity);
                    if (v == null) continue;
                    String vcn = v.getClass().getName();
                    if (vcn.contains("Player") || vcn.contains("Session")) {
                        trySetSpeed(v, speed);
                        registerPlayer(v);
                    }
                } catch (Throwable t) { /* 跳过 */ }
            }
            clz = clz.getSuperclass();
            depth++;
        }
    }

    private static boolean trySetSpeed(Object obj, float speed) {
        if (obj == null) return false;
        if (callMethodQuiet(obj, "setSpeed", speed)) return true;
        if (callMethodQuiet(obj, "setPlaySpeed", speed)) return true;
        if (callMethodQuiet(obj, "setPlaybackSpeed", speed)) return true;
        if (obj instanceof MediaPlayer) {
            try {
                MediaPlayer mp = (MediaPlayer) obj;
                PlaybackParams params = mp.getPlaybackParams();
                params.setSpeed(speed);
                mp.setPlaybackParams(params);
                return true;
            } catch (Throwable t) { /* 忽略 */ }
        }
        return false;
    }

    // ==================== 策略5: 拦截配置读取 ====================

    private void hookPrefsRead() {
        try {
            Class<?> clz = Class.forName("android.app.SharedPreferencesImpl");
            Method getFloat = findMethod(clz, "getFloat", String.class, float.class);
            if (getFloat == null) return;

            addHook(getFloat, chain -> {
                // 模块自己读配置时直接放行，避免 getSpeedConfig -> getFloat -> hook 递归
                if (Boolean.TRUE.equals(readingConfig.get())) {
                    return chain.proceed();
                }
                Object result = chain.proceed();
                try {
                    List<Object> args = chain.getArgs();
                    String key = args.isEmpty() ? null : (String) args.get(0);
                    if (key != null && key.toLowerCase().contains("speed")
                            && result instanceof Float) {
                        float original = (Float) result;
                        float target = getSpeedConfig();
                        if (Math.abs(original - target) > 0.01f) result = target;
                    }
                } catch (Throwable t) { /* 静默 */ }
                return result;
            });
            logi("策略5 配置拦截 已挂载");
        } catch (Throwable t) {
            loge("策略5 挂载失败: " + t);
        }
    }

    // ==================== 强制倍速循环 ====================

    private void startForceLoop() {
        if (loopRunning) return;
        loopRunning = true;
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (!loopRunning) return;
                try {
                    // 每个视频都可能换播放器：每轮把当前 Activity 里的播放器重新扫一遍
                    Activity activity = currentActivity();
                    if (activity != null) scanActivity(activity);

                    float target = getSpeedConfig();
                    if (Math.abs(target - 1.0f) > 0.01f) {
                        synchronized (discoveredPlayers) {
                            for (int i = discoveredPlayers.size() - 1; i >= 0; i--) {
                                Object player = discoveredPlayers.get(i);
                                if (trySetSpeed(player, target)) {
                                    playerFailures.remove(player);
                                } else {
                                    Integer failures = playerFailures.get(player);
                                    int next = failures == null ? 1 : failures + 1;
                                    if (next >= MAX_PLAYER_FAILURES) {
                                        discoveredPlayers.remove(i);
                                        playerFailures.remove(player);
                                    } else {
                                        playerFailures.put(player, next);
                                    }
                                }
                            }
                        }
                    }
                } catch (Throwable t) { /* 静默 */ }
                // 常驻循环，进程结束才停止；不再限制轮数
                handler.postDelayed(this, SPEED_FORCE_INTERVAL_MS);
            }
        });
        logi("强制倍速循环已启动（常驻）");
    }

    private static Activity currentActivity() {
        try {
            Object activity = Class.forName("android.app.ActivityThread")
                    .getMethod("currentActivity")
                    .invoke(null);
            return activity instanceof Activity ? (Activity) activity : null;
        } catch (Throwable t) {
            return null;
        }
    }

    // ==================== 配置 ====================

    /**
     * 抖音切换视频/重建播放器时会把速度重置为 1.0，这不是用户手动设置，
     * 不能当成“跟随倍速”记下来，否则一个视频之后就永久变成 1 倍速。
     */
    private static boolean shouldCapture(float speed) {
        if (Math.abs(speed - 1.0f) < 0.01f && !isManual()) return false;
        return true;
    }

    /** 升级后只清一次：旧版本把抖音内部 1.0 重置误记成了跟随倍速 */
    private void clearStaleCapturedOnce() {
        readingConfig.set(Boolean.TRUE);
        try {
            Application app = currentApplication();
            if (app == null) return;
            SharedPreferences sp = app.getSharedPreferences(CAPTURED_PREFS, Context.MODE_PRIVATE);
            if (!sp.getBoolean(CAPTURED_FIX_MARKER, false)) {
                sp.edit()
                        .remove(PrefsConstants.KEY_SPEED)
                        .putBoolean(CAPTURED_FIX_MARKER, true)
                        .commit();
                logi("已清理旧版残留的误捕获倍速");
            }
        } catch (Throwable t) {
            loge("清理残留倍速失败: " + t);
        } finally {
            readingConfig.remove();
        }
    }

    private float getSpeedConfig() {
        // 重入时返回默认值，绝不再触发一轮配置读取（否则 getFloat hook 会无限递归）
        if (Boolean.TRUE.equals(readingConfig.get())) {
            return PrefsConstants.DEFAULT_SPEED;
        }
        readingConfig.set(Boolean.TRUE);
        try {
            // 抖音自身设置的倍速拥有最高优先级
            float captured = getCapturedSpeed();
            if (captured > 0f) return captured;

            // 回退：模块 App 通过 RemotePreferences 写入的配置
            try {
                if (modulePrefs != null) {
                    return modulePrefs.getFloat(PrefsConstants.KEY_SPEED,
                            PrefsConstants.DEFAULT_SPEED);
                }
            } catch (Throwable t) { /* 静默 */ }
            return PrefsConstants.DEFAULT_SPEED;
        } finally {
            readingConfig.remove();
        }
    }

    private float getCapturedSpeed() {
        try {
            Application app = currentApplication();
            if (app != null) {
                return app.getSharedPreferences(CAPTURED_PREFS, Context.MODE_PRIVATE)
                        .getFloat(PrefsConstants.KEY_SPEED, 0f);
            }
        } catch (Throwable t) { /* 静默 */ }
        return 0f;
    }

    private void saveCapturedSpeed(float speed) {
        // 写配置也标记为“读取配置中”，避免 editor 内部读取触发策略5递归
        readingConfig.set(Boolean.TRUE);
        try {
            Application app = currentApplication();
            if (app == null) return;
            app.getSharedPreferences(CAPTURED_PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putFloat(PrefsConstants.KEY_SPEED, speed)
                    .commit();
            logi("捕获跟随倍速: " + speed + "x");
            // 说明：hook 进程内 RemotePreferences 只读，因此不再直接改写模块 App 的
            // shared_prefs 文件以同步 UI；模块 UI 显示的是用户配置值。
        } catch (Throwable t) {
            loge("保存跟随倍速失败: " + t);
        } finally {
            readingConfig.remove();
        }
    }

    private static Application currentApplication() {
        if (application != null) return application;
        try {
            application = (Application) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication")
                    .invoke(null);
        } catch (Throwable t) { /* 静默 */ }
        return application;
    }

    private static boolean isManual() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 3; i < Math.min(25, stack.length); i++) {
            String mn = stack[i].getMethodName().toLowerCase();
            String cn = stack[i].getClassName().toLowerCase();
            if (mn.equals("onclick") || mn.equals("performclick") || mn.equals("ontouchevent")
                    || mn.equals("onitemclick") || mn.equals("dispatchtouchevent")
                    || mn.equals("oncheckedchanged") || mn.contains("onclick"))
                return true;
            if (cn.contains("speed") && (mn.contains("click") || mn.contains("touch")))
                return true;
        }
        return false;
    }

    // ==================== 反射与日志工具（替代 XposedHelpers） ====================

    private static Method findMethod(Class<?> clz, String name, Class<?>... params) {
        Class<?> c = clz;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    private static boolean callMethodQuiet(Object target, String name, Object... args) {
        if (target == null) return false;
        Class<?> clz = target.getClass();
        while (clz != null && clz != Object.class) {
            for (Method m : clz.getDeclaredMethods()) {
                if (!m.getName().equals(name)) continue;
                if (!matchesArguments(m.getParameterTypes(), args)) continue;
                try {
                    m.setAccessible(true);
                    Class<?>[] types = m.getParameterTypes();
                    Object[] coerced = new Object[args.length];
                    for (int i = 0; i < args.length; i++) {
                        coerced[i] = coerceNumber(args[i], types[i]);
                    }
                    m.invoke(target, coerced);
                    return true;
                } catch (Throwable t) { /* 尝试下一个候选 */ }
            }
            clz = clz.getSuperclass();
        }
        return false;
    }

    private static boolean matchesArguments(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            Object arg = args[i];
            if (arg == null) {
                if (types[i].isPrimitive()) return false;
                continue;
            }
            Class<?> boxed = box(types[i]);
            if (boxed.isInstance(arg)) continue;
            // 播放器 setSpeed 可能是 double/int，允许 Number 自动转换
            if (arg instanceof Number && isNumeric(boxed)) continue;
            return false;
        }
        return true;
    }

    private static boolean isNumeric(Class<?> type) {
        return type == Float.class || type == Double.class || type == Integer.class
                || type == Long.class || type == Short.class;
    }

    private static Object coerceNumber(Object arg, Class<?> type) {
        if (!(arg instanceof Number)) return arg;
        Number n = (Number) arg;
        Class<?> boxed = box(type);
        if (boxed == Float.class) return n.floatValue();
        if (boxed == Double.class) return n.doubleValue();
        if (boxed == Integer.class) return n.intValue();
        if (boxed == Long.class) return n.longValue();
        if (boxed == Short.class) return n.shortValue();
        return arg;
    }

    private static Class<?> box(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }

    private void logi(String msg) {
        try {
            log(Log.INFO, TAG, msg);
        } catch (Throwable ignored) { /* 框架未就绪 */ }
    }

    private void loge(String msg) {
        try {
            log(Log.ERROR, TAG, msg);
        } catch (Throwable ignored) { /* 框架未就绪 */ }
    }
}
