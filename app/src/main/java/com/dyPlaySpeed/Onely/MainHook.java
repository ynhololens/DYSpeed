package com.dyPlaySpeed.Onely;

/**
 * 抖音播放倍速 - 主Hook类
 * 核心思路：SimPlayer 每次方法调用后自动设倍速
 * 
 * @author MarsGao
 */
public class MainHook implements de.robv.android.xposed.IXposedHookLoadPackage {

    private static final long RETRY_DELAY_MS = 3000;
    private static final long SPEED_FORCE_INTERVAL_MS = 2000;

    private static volatile boolean strategy1Hooked = false;
    private static volatile boolean strategy2Hooked = false;
    private static volatile boolean strategy3Hooked = false;
    private static volatile boolean strategy4Hooked = false;

    private static final java.util.ArrayList<Object> discoveredPlayers = new java.util.ArrayList<>();

    public final static String HOOK_PACKAGE_DY0 = "com.ss.android.ugc.aweme";
    public final static String HOOK_PACKAGE_DY1 = "com.ss.android.ugc.aweme.lite";
    public final static String HOOK_PACKAGE_DY2 = "com.ss.android.ugc.live";
    public final static String HOOK_PACKAGE_DY3 = "com.ss.android.ugc.aweme.mobile";

    // ==================== 主入口 ====================
    @Override
    public void handleLoadPackage(final de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            String pkg = lpparam.packageName;
            String proc = lpparam.processName;
            if (!isDouyinPackage(pkg)) return;
            if (!proc.equals(pkg)) return;

            hookAll(lpparam);
            延迟重试_并启动强制倍速(lpparam);

        } catch (Throwable t) {
            de.robv.android.xposed.XposedBridge.log(t);
        }
    }

    private boolean isDouyinPackage(String pkg) {
        return HOOK_PACKAGE_DY0.equals(pkg) || HOOK_PACKAGE_DY1.equals(pkg)
            || HOOK_PACKAGE_DY2.equals(pkg) || HOOK_PACKAGE_DY3.equals(pkg);
    }

    // ==================== 初始化所有Hook ====================
    private void hookAll(final de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpparam) {
        safeHook(() -> hookSimPlayer(lpparam));
        safeHook(() -> hook通用扫描(lpparam));
        safeHook(() -> hookMediaPlayer());
        safeHook(() -> hookActivity反射(lpparam));
        safeHook(() -> hook拦截配置());
    }

    // ==================== 延迟重试 + 强制倍速 ====================
    private void 延迟重试_并启动强制倍速(final de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpparam) {
        new Thread(() -> {
            try { Thread.sleep(RETRY_DELAY_MS); } catch (InterruptedException e) { return; }
            if (!strategy1Hooked) safeHook(() -> hookSimPlayer(lpparam));
            if (!strategy2Hooked) safeHook(() -> hook通用扫描(lpparam));
            if (!strategy3Hooked) safeHook(() -> hookMediaPlayer());
            if (!strategy4Hooked) safeHook(() -> hookActivity反射(lpparam));
            safeHook(() -> hook拦截配置());
            启动强制倍速循环();
        }).start();
    }

    // ==================== 策略1: SimPlayer ====================
    private void hookSimPlayer(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpparam) {
        if (strategy1Hooked) return;
        try {
            Class<?> clazz = lpparam.classLoader.loadClass(
                "com.ss.android.ugc.aweme.video.simplayer.SimPlayer");

            String[] speedMethods = {"setSpeed", "setPlaySpeed", "setPlaybackSpeed", "setSpeedInMultiplier"};
            for (String mn : speedMethods) {
                try {
                    de.robv.android.xposed.XposedBridge.hookAllMethods(clazz, mn,
                        new de.robv.android.xposed.XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) {
                                try {
                                    if (param.args != null && param.args.length > 0
                                        && param.args[0] instanceof Number) {
                                        float target = getSpeedConfig();
                                        float cur = ((Number) param.args[0]).floatValue();
                                        if (Math.abs(cur - target) > 0.01f && !isManual()) {
                                            param.args[0] = target;
                                            de.robv.android.xposed.XposedBridge.log(
                                                "[抖音倍速] 拦截 " + cur + "->" + target + "x");
                                        }
                                    }
                                } catch (Throwable t) { /* 静默 */ }
                            }

                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    float target = getSpeedConfig();
                                    if (Math.abs(target - 1.0f) > 0.01f) {
                                        扫描内部(param.thisObject, target);
                                    }
                                } catch (Throwable t) { /* 静默 */ }
                            }
                        });
                } catch (Throwable t) { /* 忽略 */ }
            }

            // Hook 所有非抽象方法：每次调用后自动设倍速
            for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
                String mn = m.getName();
                if (mn.startsWith("access$") || mn.startsWith("get")
                    || mn.equals("toString") || mn.equals("hashCode")
                    || mn.equals("equals") || mn.equals("clone")
                    || java.lang.reflect.Modifier.isAbstract(m.getModifiers())) continue;
                boolean already = false;
                for (String sm : speedMethods) { if (mn.equals(sm)) { already = true; break; } }
                if (already) continue;

                m.setAccessible(true);
                de.robv.android.xposed.XposedBridge.hookMethod(m,
                    new de.robv.android.xposed.XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                float target = getSpeedConfig();
                                if (Math.abs(target - 1.0f) > 0.01f) {
                                    de.robv.android.xposed.XposedHelpers.callMethod(
                                        param.thisObject, "setSpeed", target);
                                    de.robv.android.xposed.XposedBridge.log(
                                        "[抖音倍速] 设置 " + target + "x");
                                }
                            } catch (Throwable t) { /* 静默 */ }
                        }
                    });
            }
            strategy1Hooked = true;
        } catch (ClassNotFoundException e) { /* 忽略 */ }
    }

    // ==================== SimPlayer 内部扫描 ====================
    private void 扫描内部(Object obj, float speed) {
        if (obj == null) return;
        java.util.IdentityHashMap<Object, Boolean> visited = new java.util.IdentityHashMap<>();
        递归扫描(obj, speed, visited, 0);
    }

    private void 递归扫描(Object obj, float speed,
                         java.util.IdentityHashMap<Object, Boolean> visited, int depth) {
        if (obj == null || depth > 4 || visited.containsKey(obj)) return;
        visited.put(obj, Boolean.TRUE);

        String[] methods = {"setSpeed", "setPlaySpeed", "setPlaybackSpeed"};
        Class<?> clz = obj.getClass();
        String cn = clz.getName();
        if (cn.startsWith("java.") || cn.startsWith("android.")) return;

        int d = 0;
        while (clz != null && clz != Object.class && d < 6) {
            for (java.lang.reflect.Field f : clz.getDeclaredFields()) {
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
                        try {
                            de.robv.android.xposed.XposedHelpers.callMethod(v, mn, speed);
                            synchronized (discoveredPlayers) {
                                if (!discoveredPlayers.contains(v)) discoveredPlayers.add(v);
                            }
                        } catch (Throwable t) { /* 忽略 */ }
                    }
                    递归扫描(v, speed, visited, depth + 1);
                } catch (Exception e) { /* 跳过 */ }
            }
            clz = clz.getSuperclass();
            d++;
        }
    }

    // ==================== 策略2: 通用扫描 ====================
    private void hook通用扫描(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpparam) {
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
            String fullName = pkgCls[0] + "." + pkgCls[1];
            try {
                Class<?> clazz = lpparam.classLoader.loadClass(fullName);
                for (String mn : speedMethods) {
                    try {
                        java.lang.reflect.Method m = de.robv.android.xposed.XposedHelpers
                            .findMethodExactIfExists(clazz, mn, float.class);
                        if (m != null) {
                            de.robv.android.xposed.XposedBridge.hookMethod(m,
                                                new de.robv.android.xposed.XC_MethodHook() {
                                                    @Override
                                                    protected void beforeHookedMethod(MethodHookParam param) {
                                                        try {
                                                            float target = getSpeedConfig();
                                                            float cur = (float) param.args[0];
                                                            if (Math.abs(cur - target) > 0.01f && !isManual()) {
                                                                param.args[0] = target;
                                                                de.robv.android.xposed.XposedBridge.log(
                                                                    "[抖音倍速] 拦截 " + cur + "->" + target + "x");
                                                            }
                                                        } catch (Throwable t) { /* 静默 */ }
                                                    }
                                                });
                            hooked++;
                        }
                    } catch (Throwable t) { /* 忽略 */ }
                }
            } catch (ClassNotFoundException e) { /* 忽略 */ }
        }
        if (hooked > 0) strategy2Hooked = true;
    }

    // ==================== 策略3: MediaPlayer ====================
    private void hookMediaPlayer() {
        if (strategy3Hooked) return;
        try {
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(
                android.media.MediaPlayer.class, "setPlaybackParams",
                android.media.PlaybackParams.class,
                new de.robv.android.xposed.XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            android.media.PlaybackParams params = (android.media.PlaybackParams) param.args[0];
                            float cur = params.getSpeed();
                            float target = getSpeedConfig();
                            if (Math.abs(cur - 1.0f) < 0.01f && Math.abs(target - 1.0f) > 0.01f && !isManual()) {
                                params.setSpeed(target);
                                param.args[0] = params;
                            }
                        } catch (Throwable t) { /* 静默 */ }
                    }
                });
            strategy3Hooked = true;
        } catch (Throwable t) { /* 忽略 */ }
    }

    // ==================== 策略4: Activity 反射 ====================
    private void hookActivity反射(de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam lpparam) {
        if (strategy4Hooked) return;
        try {
            Class<?> activityClass = lpparam.classLoader.loadClass("android.app.Activity");
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(activityClass, "onResume",
                new de.robv.android.xposed.XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            String name = param.thisObject.getClass().getName();
                            if (!name.contains("video") && !name.contains("Video")
                                && !name.contains("feed") && !name.contains("Feed")
                                && !name.contains("aweme") && !name.contains("Aweme")) return;
                            new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(() -> 扫描Activity(param.thisObject), 800);
                        } catch (Throwable t) { /* 静默 */ }
                    }
                });
            strategy4Hooked = true;
        } catch (Exception e) { /* 忽略 */ }
    }

    private void 扫描Activity(Object activity) {
        float speed = getSpeedConfig();
        Class<?> clz = activity.getClass();
        int depth = 0;
        while (clz != null && clz != Object.class && depth < 8) {
            for (java.lang.reflect.Field f : clz.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(activity);
                    if (v == null) continue;
                    String vcn = v.getClass().getName();
                    if (vcn.contains("Player") || vcn.contains("Session")) {
                        trySetSpeed(v, speed);
                        synchronized (discoveredPlayers) {
                            if (!discoveredPlayers.contains(v)) discoveredPlayers.add(v);
                        }
                    }
                } catch (Exception e) { /* 跳过 */ }
            }
            clz = clz.getSuperclass();
            depth++;
        }
    }

    private void trySetSpeed(Object obj, float speed) {
        if (obj == null) return;
        try {
            de.robv.android.xposed.XposedHelpers.callMethod(obj, "setSpeed", speed);
        } catch (Throwable t) {
            try { de.robv.android.xposed.XposedHelpers.callMethod(obj, "setPlaySpeed", speed); }
            catch (Throwable t2) { /* 忽略 */ }
        }
    }

    // ==================== 策略5: 拦截配置读取 ====================
    private void hook拦截配置() {
        try {
            Class<?> clz = Class.forName("android.app.SharedPreferencesImpl");
            de.robv.android.xposed.XposedHelpers.findAndHookMethod(clz,
                "getFloat", String.class, float.class,
                new de.robv.android.xposed.XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            String key = (String) param.args[0];
                            float original = (float) param.getResult();
                            if (key != null && key.toLowerCase().contains("speed")) {
                                float target = getSpeedConfig();
                                if (Math.abs(original - target) > 0.01f) {
                                    param.setResult(target);
                                }
                            }
                        } catch (Throwable t) { /* 静默 */ }
                    }
                });
        } catch (ClassNotFoundException e) { /* 忽略 */ }
    }

    // ==================== 强制倍速循环 (200ms间隔) ====================
    private void 启动强制倍速循环() {
        try {
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(new Runnable() {
                int count = 0;
                @Override
                public void run() {
                    try {
                        float target = getSpeedConfig();
                        if (Math.abs(target - 1.0f) > 0.01f) {
                            synchronized (discoveredPlayers) {
                                for (int i = discoveredPlayers.size() - 1; i >= 0; i--) {
                                    try {
                                        de.robv.android.xposed.XposedHelpers.callMethod(
                                            discoveredPlayers.get(i), "setSpeed", target);
                                    } catch (Throwable t) {
                                        discoveredPlayers.remove(i);
                                    }
                                }
                            }
                        }
                    } catch (Throwable t) { /* 静默 */ }
                    if (++count < 60) handler.postDelayed(this, SPEED_FORCE_INTERVAL_MS);
                }
            });
        } catch (Throwable t) { /* 静默 */ }
    }

    // ==================== 配置 ====================
    private static de.robv.android.xposed.XSharedPreferences prefs =
        new de.robv.android.xposed.XSharedPreferences("com.dyPlaySpeed.Onely", "speed");

    private static float getSpeedConfig() {
        try {
            prefs.reload();
            return prefs.getFloat("speed", 1.5f);
        } catch (Throwable t) { return 1.5f; }
    }

    private static boolean isManual() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 3; i < Math.min(25, stack.length); i++) {
            String mn = stack[i].getMethodName().toLowerCase();
            String cn = stack[i].getClassName().toLowerCase();
            if (mn.equals("onclick") || mn.equals("performclick") || mn.equals("ontouchevent"))
                return true;
            if (cn.contains("speed") && (mn.contains("click") || mn.contains("touch")))
                return true;
        }
        return false;
    }

    private void safeHook(Runnable hook) {
        try { hook.run(); } catch (Throwable t) { /* 静默 */ }
    }
}
