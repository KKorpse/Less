# Less

一个为 **OPPO Pad Mini（OPD2515，ColorOS 16 / Android 16 / API 36）** 打造的本机相册。界面克制、暗色、无广告、无 `INTERNET` 权限，用来安静地滑动浏览设备里的照片和视频。

## 功能

- **本机媒体网格**：读取 MediaStore 中的照片与视频，按日期分组，支持「全部 / 照片 / 视频」筛选。
- **全屏滑动浏览**：左右滑动切换；顶栏显示当前位置和视频文件名，图片页底部显示文件名、拍摄时间、分辨率与大小。
- **图片缩放**：双指缩放、双击放大，放大后可拖动查看细节；缩放状态下不会误触翻页。
- **视频播放**：基于 Media3 / ExoPlayer，支持 H.264 / H.265 等系统可解码格式，带播放控件。
- **沉浸式界面**：自动隐藏顶栏、底栏和系统栏，轻点画面恢复；浏览与播放时保持屏幕常亮。
- **三种访问方式**：
  1. 允许访问全部照片和视频（`READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO`）；
  2. Android 14+ 的「选择部分照片」授权（`READ_MEDIA_VISUAL_USER_SELECTED`），只浏览已选内容；
  3. 完全不给权限时，使用系统 Photo Picker 临时挑选照片 / 视频。
- **隐私**：没有 `INTERNET` 权限，不上传任何内容；没有 `MANAGE_EXTERNAL_STORAGE`，不绕过系统媒体库。

## 环境

| 项目 | 值 |
| --- | --- |
| 目标设备 | OPPO Pad Mini（OPD2515） |
| 系统 | ColorOS 16.0 / Android 16 |
| compileSdk / targetSdk | 36 |
| minSdk | 29（Android 10） |
| 语言 | Kotlin |
| 构建 | Gradle 8.11.1 + AGP 8.9.1 + JDK 17 |
| 图片加载 | Coil 3（含视频抽帧） |
| 视频播放 | Media3 ExoPlayer 1.7.1 |
| 手势缩放 | PhotoView 2.3.0 |

> Android 16 延续了 Android 14 的部分照片授权机制。应用会根据授权状态在「全部图库 / 部分内容 / 系统选择器」之间切换；只有点击界面按钮时才请求权限，不会在每次启动时反复弹窗。

应用不包含任何 `.so` 原生库，因此天然兼容 Android 15 / 16 的 16 KB 页大小设备。

## 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleDebug
```

产物：

```
app/build/outputs/apk/debug/Less-v1.0.0-debug.apk
```

复制一份到 `dist/`：

```bash
mkdir -p dist
cp app/build/outputs/apk/debug/Less-v1.0.0-debug.apk dist/
```

## 安装到平板

打开开发者选项和 USB 调试，连接设备后：

```bash
# 使用 Android SDK 里的 adb
~/Library/Android/sdk/platform-tools/adb install -r dist/Less-v1.0.0-debug.apk

# 启动
~/Library/Android/sdk/platform-tools/adb shell am start -n com.kkorpse.less/.ui.MainActivity
```

也可以使用仓库内的脚本：

```bash
./install.sh
```

如果是在平板上用文件管理器直接打开 APK 安装，需要在 ColorOS 中允许「安装外部来源应用」。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| `READ_MEDIA_IMAGES` | Android 13+ 读取照片 |
| `READ_MEDIA_VIDEO` | Android 13+ 读取视频 |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Android 14+ 允许「选择部分照片」并展示已选内容 |
| `READ_EXTERNAL_STORAGE` | 仅 Android 10–12 使用（`maxSdkVersion=32`） |

首次进入应用时会展示权限说明页，由用户主动点击「允许访问」后才触发系统弹窗。若拒绝，可直接使用系统 Photo Picker，或前往系统设置重新授权。

## 项目结构

```
app/src/main/java/com/kkorpse/less/
├── LessApp.kt                  # Coil ImageLoader（含视频抽帧解码器）
├── data/
│   ├── MediaItem.kt            # 统一的图片 / 视频元数据
│   ├── MediaRepository.kt      # MediaStore 查询、Picker URI 解析
│   └── MediaSession.kt         # 进程内浏览会话
├── ui/
│   ├── MainActivity.kt         # 权限、图库网格、系统选择器
│   ├── GalleryAdapter.kt       # 按日期分组的网格
│   ├── ViewerActivity.kt       # 全屏沉浸式浏览
│   ├── ViewerAdapter.kt        # 图片缩放 / ExoPlayer 视频页
│   └── SquareFrameLayout.kt    # 正方形网格单元
└── util/
    ├── Dimens.kt
    └── Format.kt               # 日期、文件大小、时长格式化
```

## 已知边界

- 系统 Photo Picker 选中的内容按 URI 临时授权即时浏览；应用进程被系统杀掉后，这组临时选择不会持久保存。
- 图片的 HDR / 10-bit 显示效果取决于系统解码与屏幕能力，视频解码取决于设备硬件解码器。
- 当前版本只读，不提供删除、编辑、分享或相册管理功能。
- 首次扫描大图库需要一点时间，MediaStore 对刚拷贝进来的文件也可能有扫描延迟，可下拉刷新。
