# DYSpeed - 抖音视频倍速控制 (LSPosed/Xposed模块)

自动控制抖音播放倍速，支持**跟随模式**——自动捕获抖音的倍速设置并沿用。

## 功能

- **强制倍速**：按模块配置覆盖抖音播放倍速
- **跟随模式（默认）**：自动捕获抖音当前设置的倍速，后续视频沿用相同倍速
  - 抖音设置 2.0x → 模块自动跟随 2.0x
  - 抖音设置 1.0x（正常）→ 模块放手，不干预
  - 抖音权限最高，模块 UI 配置仅作为无捕获值时的回退
- **多策略 Hook**：覆盖 SimPlayer、MediaPlayer、Activity 反射等多种播放路径
- **强制循环**：每 2 秒检查并保持倍速，持续 2 分钟
- **手动操作检测**：用户在抖音 UI 手动调倍速时，不拦截，直接捕获沿用

## 如何使用

1. 在 LSPosed 中激活模块，勾选抖音（com.ss.android.ugc.aweme）
2. 打开模块 UI 设置初始倍速（可选，默认 1.5x）
3. 打开抖音，在播放器中调到你想要的倍速
4. 模块自动捕获该倍速，后续视频皆以此倍速播放
5. 如需更换倍速，在抖音中重新调整即可

## 下载

预编译模块 APK 位于仓库的 [`release/`](release/) 目录：

| 文件 | 版本 | 说明 |
|------|------|------|
| [`DYSpeed-v1.3.0.apk`](release/DYSpeed-v1.3.0.apk) | v1.3.0 | 最新版，支持抖音 38.4.0 |

下载后在 LSPosed 中激活并勾选抖音即可使用。

## 目标应用

- com.ss.android.ugc.aweme（抖音）
- com.ss.android.ugc.aweme.lite（抖音极速版）
- com.ss.android.ugc.live（抖音火山版）
- com.ss.android.ugc.aweme.mobile

## 构建

```bash
./gradlew assembleRelease
```

## 技术细节

### 跟随模式实现

```
抖音 setSpeed(2.0)
  → beforeHookedMethod 捕获 2.0
  → 写入抖音进程 dy_speed_follow.xml（持久化）
  → 同步更新模块 speed.xml（UI 同步显示）
  → getSpeedConfig() 优先返回捕获值
  → 强制循环保持此倍速
```

捕获的倍速存储在抖音进程数据目录下（`dy_speed_follow.xml`），持久化生效。

### Hook 策略

| 策略 | 目标类 | 方式 |
|------|--------|------|
| SimPlayer | com.ss.android.ugc.aweme.video.simplayer.SimPlayer | 拦截 setSpeed/setPlaySpeed/setPlaybackSpeed |
| 通用扫描 | AbsPlayer, Player, IMediaPlayer, HorizonPlayerController 等 | 按方法名+签名 Hook |
| MediaPlayer | android.media.MediaPlayer | 拦截 setPlaybackParams |
| Activity 反射 | Activity.onResume | 反射扫描 Player/Session 字段 |
| 配置拦截 | SharedPreferencesImpl.getFloat | 返回值替换 |

## 许可

GPL v3
