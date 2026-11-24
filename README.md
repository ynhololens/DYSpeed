# DYSpeed - 抖音视频倍速控制（LSPosed API 102 / libxposed）

自动控制抖音播放倍速，支持**跟随模式**——自动捕获抖音的倍速设置并沿用。

## 环境要求

- **框架**：LSPosed 2.1/2.2+（需实现 libxposed API 102）；旧版 LSPosed / XposedBridge 无法加载本模块
- **系统**：Android 8.0（API 26）及以上
- **编译**：JDK 17 + Android SDK 36（libxposed API 102 的 class 文件为 Java 17，service AAR 要求 compileSdk ≥ 36）

> API 102 禁止模块调用 `de.robv.android.xposed.*` 遗留 API，本仓库已完成迁移：
> 入口改为 `META-INF/xposed/java_init.list` + `XposedModule`，Hook 改为拦截器链模型，
> 配置读取改为 RemotePreferences。不能与 legacy 版本混用。

## 功能

- **强制倍速**：按模块配置覆盖抖音播放倍速
- **跟随模式（默认）**：自动捕获抖音当前设置的倍速，后续视频沿用相同倍速
  - 抖音设置 2.0x → 模块自动跟随 2.0x
  - 抖音设置 1.0x（正常）→ 模块放手，不干预
  - 抖音权限最高，模块 UI 配置仅作为无捕获值时的回退
- **多策略 Hook**：按抖音版本自动选择旧版 SimPlayer 路径或 41.x 新倍速路径；MediaPlayer、Activity 反射、配置拦截为通用策略
- **强制循环**：每 2 秒检查并保持倍速，常驻运行（进程结束才停止）
- **手动操作检测**：用户在抖音 UI 手动调倍速时，不拦截，直接捕获沿用
- **不支持热重载**：模块含后台定时循环，`onHotReloading()` 返回 `false`；升级后请重启抖音

## 版本兼容

模块在 `onPackageReady` 读取抖音 `versionName` / `versionCode`，按主版本选择策略：

| 抖音版本 | 使用策略 |
|---|---|
| 38.x（实测 38.4.0，versionCode 380401） | legacy：SimPlayer + 通用类扫描 |
| 41.x 及以上（实测 41.2.0，versionCode 410201） | v41：`DSpeedPlayProvider.LLIIJI` + `X.1IJQ.LJJJJI` |
| 版本信息读取失败 | 两套策略都尝试挂载，靠类加载失败静默跳过 |

切换版本后只需重启抖音即可，不需要重装本模块。

## 如何使用

1. 在 LSPosed 中激活模块，勾选抖音（com.ss.android.ugc.aweme）
2. 打开模块 UI 设置初始倍速（可选，默认 1.5x）
3. 打开抖音，在播放器中调到你想要的倍速
4. 模块自动捕获该倍速，后续视频皆以此倍速播放
5. 如需更换倍速，在抖音中重新调整即可

模块 UI 通过 libxposed service 的 **RemotePreferences** 读写配置；若提示“Xposed 服务未连接”，
请确认 LSPosed 已启用本模块，并至少打开过一次模块 App。

## 下载与构建

仓库 `release/` 目录中已提供 API 102 构建产物：

- `DYSpeed-API102-20261124.apk`：当前版本模块（debug 签名，可直接安装）
- `DYSpeed-API102-20261124.apk.sha256`：SHA-256 校验文件
- `DYSpeed-v38.4.0.apk`：**旧版 legacy 构建**，不能当作 API 102 版本使用

该产物已实测兼容抖音 38.4.0 与 41.2.0，按版本自动切换 Hook 策略。自行构建：

```bash
./gradlew assembleDebug
```

DEBUG 包输出在 `app/build/outputs/apk/debug/app-debug.apk`。

Release 签名需要环境变量 `SIGNING_KEY_ALIAS` / `SIGNING_STORE_PASSWORD` / `SIGNING_KEY_PASSWORD`，
密钥文件放在 `/tmp/keystore.jks`（可按本地环境修改 `app/build.gradle` 的 `signingConfigs.release`）。

> 依赖说明：`io.github.libxposed:service:102.0.0` 要求 compileSdk 37，超出 AGP 8.13.2 支持范围，
> 因此使用兼容 API 102 框架的 `service:101.0.0`（RemotePreferences 能力一致）。

## 目标应用

- com.ss.android.ugc.aweme（抖音）
- com.ss.android.ugc.aweme.lite（抖音极速版）
- com.ss.android.ugc.live（抖音火山版）
- com.ss.android.ugc.aweme.mobile

## 技术细节

### 跟随模式实现

```
抖音 setSpeed(2.0)
  → Hook 拦截器前置逻辑捕获 2.0
  → 写入抖音进程 dy_speed_follow.xml（持久化）
  → getSpeedConfig() 优先返回捕获值
  → 强制循环保持此倍速
```

> API 102 下 hook 进程中的 RemotePreferences 是只读的，因此不再把捕获值回写到模块 App 的
> `speed.xml`；模块 UI 显示的是用户配置值，而不是运行时捕获值。

### Hook 模型（libxposed API 102）

```java
hook(method)
    .setId("dyspeed.xxx")
    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
    .intercept(chain -> {
        List<Object> args = chain.getArgs();   // 不可变 List，不能直接 set
        Object[] newArgs = args.toArray();
        newArgs[0] = 2.0f;
        Object result = chain.proceed(newArgs); // 用新参数放行
        return result;                          // 返回值直接改写
    });
```

| 策略 | 适用版本 | 目标类 | 方式 |
|------|----------|--------|------|
| SimPlayer | 38.x（legacy） | `SimPlayer` | 显式 hook `setSpeed` / `setPlaySpeed` / `setPlaybackSpeed` / `setSpeedInMultiplier`，并在其余方法调用后补设倍速 |
| 通用扫描 | 38.x（legacy） | AbsPlayer, Player, IMediaPlayer, HorizonPlayerController 等 | 逐类逐方法注册显式 hook |
| 倍速配置入口 | 41.x+ | `DSpeedPlayProvider.LLIIJI` | 改写配置对象 `LIZIZ` 字段 |
| 播放器核心 | 41.x+ | `X.1IJQ.LJJJJI(F)` | 改写下发给播放器的倍速参数 |
| MediaPlayer | 通用 | `android.media.MediaPlayer` | 拦截 `setPlaybackParams` 并修改 `PlaybackParams` |
| Activity 反射 | 通用 | `Activity.onResume` | 反射扫描 Player/Session 字段 |
| 配置拦截 | 通用 | `SharedPreferencesImpl.getFloat` | 改写返回值 |

## 许可

GPL v3
