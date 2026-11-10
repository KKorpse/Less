# Less

为 **OPPO Pad Mini（OPD2515，ColorOS 16 / Android 16 / API 36）** 做的本机相册。界面克制、暗色、无广告、无 `INTERNET` 权限。

2.0 版本从「图库网格」改成了以浏览动作为中心的结构：首页进入照片、视频、统计；照片按组以扑克牌形式进入专注浏览；视频是抖音式纵向流。

## 功能

### 首页

- 底部三个入口：**照片 / 视频 / 统计**
- 右上角：**收藏**、**设置**
- 部分照片授权时，顶部显示「仅显示你已选择的照片和视频」和「管理」入口

### 照片

- 默认展示三张照片，像扑克牌一样重叠
- 点击任意卡片进入专注浏览
- 按设置中的「每组数量」取一组照片；随机或顺序
- 左右滑动切换，**上滑移入系统回收站**
- 四角圆角卡片，滑动跟手
- 左下角收藏，右下角撤回，左上角返回照片首页
- 底部中间显示相对时间（今天 / N 天前 / N 周前 / N 个月前 / N 年前）
- 普通照片组浏览到接近组尾时会自动补下一组；收藏列表不会补全整组图库
- 双击手势可在设置中选「收藏」或「放大」；双指缩放始终可用

### 视频

- 进入即自动播放第一个视频，上下滑动切换
- 抖音式纵向全屏流，当前视频循环播放
- 右侧由上到下：**收藏 / 删除 / 撤回**
- 删除调用系统回收站，撤回请求恢复最近一次删除
- 底部左侧显示文件名、相对时间和视频时长
- 底部有一条播放进度线

### 统计

两行：照片、视频。每行显示：

- 总数
- 已查看
- 已删除（本应用发起的删除操作数，撤回会减回去）

底部「重置浏览记录」会清空照片/视频的已查看标记，并让顺序浏览从第一张重新开始。收藏和删除记录不会被清除。

### 设置

- 每组数量：3–30 张，默认 10
- 双击手势：收藏 / 放大
- 浏览模式：随机 / 顺序

### 权限

- Android 13+：`READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO`
- Android 14+：额外申请 `READ_MEDIA_VISUAL_USER_SELECTED`，系统弹窗会出现 **Allow limited access / Allow all**
- 拒绝权限时可以继续用系统 Photo Picker 临时浏览
- 只有点击界面按钮时才请求权限，不会每次启动反复弹窗

## 收藏与删除说明

**收藏**保存在 Less 本地。ColorOS / Android 系统相册的「收藏」相册没有向第三方应用开放写入接口，所以第三方应用无法可靠地把照片直接塞进 OPPO 相册的收藏夹。本版的做法是：

- 收藏状态保存在本机
- 首页右上角心形入口集中查看收藏
- 若后续需要同步系统收藏，可以接入 `MediaStore.createFavoriteRequest`，但 Android 会为每次操作弹系统确认

**删除**使用 `MediaStore.createTrashRequest`，真正进入系统回收站，而不是直接永久删除。第三方应用操作其他应用创建的媒体时，Android 会弹出确认框；这是系统安全机制，不是应用多此一举。撤回同理，会请求把项目从回收站移出。

## 环境

| 项目 | 值 |
| --- | --- |
| 目标设备 | OPPO Pad Mini（OPD2515） |
| 系统 | ColorOS 16.0 / Android 16 / API 36 |
| 当前版本 | 2.0.2（versionCode 4） |
| compileSdk / targetSdk | 36 |
| minSdk | 29（Android 10） |
| 语言 | Kotlin |
| 构建 | Gradle 8.11.1 + AGP 8.9.1 + JDK 17 |
| 图片加载 | Coil 3（含视频抽帧） |
| 视频播放 | Media3 ExoPlayer 1.7.1 |
| 手势缩放 | PhotoView 2.3.0 |

应用不含 `.so` 原生库，兼容 Android 15 / 16 的 16 KB 页大小设备。

## 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleDebug
```

产物：

```
app/build/outputs/apk/debug/Less-v2.0.2-debug.apk
```

仓库的 `dist/` 保留了每个版本的 APK：

```
dist/Less-v1.0.0-debug.apk
dist/Less-v2.0.0-debug.apk
dist/Less-v2.0.1-debug.apk
dist/Less-v2.0.2-debug.apk
dist/SHA256SUMS.txt
```

## 安装到平板

```bash
~/Library/Android/sdk/platform-tools/adb install -r dist/Less-v2.0.2-debug.apk
~/Library/Android/sdk/platform-tools/adb shell am start -n com.kkorpse.less/.ui.MainActivity
```

或：

```bash
./install.sh
```

如果是在平板上用文件管理器直接打开 APK，需要在 ColorOS 中允许「安装外部来源应用」。

## 权限一览

| 权限 | 用途 |
| --- | --- |
| `READ_MEDIA_IMAGES` | Android 13+ 读取照片 |
| `READ_MEDIA_VIDEO` | Android 13+ 读取视频 |
| `READ_MEDIA_VISUAL_USER_SELECTED` | Android 14+ 选择部分照片授权 |
| `READ_EXTERNAL_STORAGE` | 仅 Android 10–12 使用（`maxSdkVersion=32`） |

没有 `INTERNET`，没有 `MANAGE_EXTERNAL_STORAGE`，不绕过系统媒体库。

## 验证

- `lintDebug`：通过，无 error
- Android 16 平板模拟器（Pixel Tablet，2560×1600，API 36）：
  - 权限弹窗确认出现「Allow limited access / Allow all」
  - 全部授权、部分授权均可用
  - 首页、三张卡片、照片专注、左右滑动、上滑系统回收站、撤回、收藏、相对时间
  - 视频流自动播放、上下滑动、右侧收藏/删除/撤回
  - 收藏页照片/视频
  - 统计总数/已查看/已删除、重置浏览记录
  - 设置页与切换按钮
- 用户手动测试进行中；发现问题继续递增版本号（2.0.3、2.0.4…）。

## 项目结构

```
app/src/main/java/com/kkorpse/less/
├── LessApp.kt
├── data/
│   ├── MediaItem.kt
│   ├── MediaRepository.kt
│   ├── MediaSession.kt              # 系统选择器兜底会话
│   ├── BrowseSessions.kt            # 照片组 / 视频流会话
│   └── PhotoGroupBuilder.kt         # 随机/顺序抽组
├── prefs/
│   ├── AppPreferences.kt            # 每组数量、双击手势、浏览模式
│   └── BrowseStore.kt               # 已查看、收藏、删除计数、顺序游标
├── ui/
│   ├── MainActivity.kt              # 首页 + 权限
│   ├── PhotoHomeActivity.kt         # 三张扑克牌
│   ├── PhotoFocusActivity.kt        # 照片专注浏览
│   ├── PhotoFocusAdapter.kt
│   ├── PhotoFocusViewModel.kt
│   ├── VideoFeedActivity.kt         # 抖音式视频流
│   ├── VideoFeedAdapter.kt
│   ├── VideoFeedViewModel.kt
│   ├── StatsActivity.kt
│   ├── SettingsActivity.kt
│   ├── FavoritesActivity.kt
│   ├── ViewerActivity.kt            # 系统选择器兜底浏览
│   ├── ViewerAdapter.kt
│   ├── GalleryAdapter.kt            # 收藏页网格
│   ├── FocusCardLayout.kt           # 未缩放时把滑动交给 RecyclerView
│   ├── SquareFrameLayout.kt
│   └── TrashController.kt           # 系统回收站删除/恢复
└── util/
    ├── MediaAccess.kt
    ├── Format.kt
    └── Dimens.kt
```

## 已知边界

- 系统相册的「收藏」夹没有公开写入接口，收藏目前只在 Less 内生效。
- 删除/撤回需要系统确认；确认框由 Android 媒体提供方绘制，可能是浅色样式。
- 「已删除」统计的是本应用发起的删除次数；在系统相册里手动删除不会计入。
- 收藏页只显示当前仍有权限访问、且未被移入回收站的内容。
- 视频流目前自动播放声音，没有静音开关。
- 图片 HDR / 10-bit 显示效果取决于系统解码与屏幕能力；视频解码取决于设备硬件解码器。
- 首次扫描大图库需要一点时间，MediaStore 对刚拷贝进来的文件可能有扫描延迟。

## 版本记录

见 [CHANGELOG.md](CHANGELOG.md)。
