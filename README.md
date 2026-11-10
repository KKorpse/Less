# Less

为 **OPPO Pad Mini（OPD2515，ColorOS 16 / Android 16 / API 36）** 做的本机相册。界面克制、暗色、无广告、无 `INTERNET` 权限。

2.1 版本把照片、视频、统计收敛成**同一个首页的三个模式**：底部是悬浮胶囊导航，照片模式直接展示三张扑克牌，视频模式点一下就直接进入刷视频，不再额外打开新页面。

2.2 版本继续打磨照片浏览：一组看完自动进入下一组；照片按原始比例装进圆角卡片，竖屏 2:3 时横向照片的上下空区会自动铺上照片整体色调；底部胶囊导航再压低 1/3，并给 tab 切换加了淡入淡出动效。2.2.1 继续收小圆角、放大图片可用区域，并让缩放时整张图（连同圆角）一起放大；2.2.2 把首页恢复成三张扑克牌展开的样式，并用单一路径重画卡片圆角与白边。

## 功能

### 首页

- 只有一个主页面，底部悬浮胶囊：**照片 / 视频 / 统计**
- 胶囊高度 44dp；三个模式切换使用淡出/淡入动效，照片卡片进入时带轻微缩放淡入
- 照片模式的三张卡片像扑克牌一样左右展开，左右各倾斜 9°；卡片底板、照片裁剪和白边共用同一条圆角路径，旋转后不会出现双线或锯齿
- 照片模式：
  - 左上角是「照片 + 数量」胶囊
  - 右上角是收藏、设置圆形入口
  - 三张照片缩小为带白色描边的扑克牌，居中叠放，背景是当前照片的模糊虚化
  - 点击任意卡片才进入专注浏览
  - 切到其他 tab 再回来，卡片组保留
- 视频模式：点击底部「视频」直接进入纵向刷视频，不再单独起页
- 统计模式：标题「使用统计」，右上角设置，底部导航始终悬浮

### 照片专注

- 左右滑动切换；上滑标记为待删除
- 上滑时卡片缩小、淡出、向上消失，不立即打扰系统
- 可以连续标记多张；整组看完后进入汇总页
- 汇总页显示待删除数量和缩略图，一次系统确认批量移入回收站
- 删除后可以「撤回删除」批量恢复
- 没有待删除标记时，一组看完自动加载下一组；有标记时先处理汇总页，再自动进入下一组
- 左下角圆形收藏（桃心），右下角圆形撤回（返回箭头），都不带文字；圆按钮 40dp
- 中间信息气泡与两侧圆按钮等高，两行文字都水平居中：
  - 第一行加粗：相对时间（今天 / N 天前 / N 周前 / N 个月前 / N 年前）
  - 第二行小号灰色：拍摄地址
- 气泡右侧有一个信息按钮，可查看文件名、完整时间、地址
- 图片可用区域：左右各留 12dp，上到状态栏下方，下到收藏/时间/撤回按钮上方；方图抵满左右，长图抵满上下
- 圆角 16dp；缩放时裁剪范围跟着图片走，整张图连同圆角一起放大，不会出现固定大小的画框
- 整页背景根据当前照片整体色调生成深色渐变；竖屏 2:3 下横向照片的上下空区会被照片色调填满
- 地址来自照片 EXIF GPS，首次进入照片专注会请求一次位置权限
- 没有 GPS 时显示「未记录拍摄地点」；无法反查地址时回退为坐标

### 视频

- 纵向全屏流，进入自动播放，上下滑动切换
- 右侧无文字圆形按钮，由上到下：**收藏 / 删除 / 撤回**
- 左下角显示相对时间和时长胶囊
- 底部细进度线
- 视频仍然即时删除/撤回，调用系统回收站
- 悬浮胶囊导航保持可见，可在视频中直接切到照片/统计

### 统计

「使用统计」页面：

- 照片、视频两张卡片
- 每张卡片：**总数 / 查看 / 删除**
- 查看、删除带蓝色/红色图标
- 底部「重置浏览记录」卡片显示已查看/已删除数量，点击确认后清空已查看标记
- 顺序浏览游标一并重置；收藏和删除计数不清除

### 设置

- 每组数量：3–30 张，默认 10
- 双击手势：收藏 / 放大
- 浏览模式：随机 / 顺序

### 权限

- Android 13+：`READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO`
- Android 14+：额外申请 `READ_MEDIA_VISUAL_USER_SELECTED`，系统弹窗会出现 **Allow limited access / Allow all**
- 拒绝媒体权限时可以继续用系统 Photo Picker 临时浏览
- 拍摄地址：`ACCESS_MEDIA_LOCATION`，在第一次进入照片专注时单独询问，只问一次
- 只有点击界面按钮时才请求权限，不会每次启动反复弹窗

## 收藏与删除说明

**收藏**保存在 Less 本地。ColorOS / Android 系统相册的「收藏」相册没有向第三方应用开放写入接口，第三方应用无法可靠地把照片直接塞进 OPPO 相册的收藏夹。本版：

- 收藏状态保存在本机
- 首页右上角心形入口集中查看收藏
- 若后续需要同步系统收藏，可以接入 `MediaStore.createFavoriteRequest`，但 Android 会为每次操作弹系统确认

**照片删除**分两步：

1. 上滑只做标记，卡片缩小淡出，不打扰系统；
2. 组尾汇总页确认后，调用 `MediaStore.createTrashRequest` 批量移入系统回收站。

第三方应用操作其他应用创建的媒体时，Android 会弹出确认框，这是系统安全机制。视频仍然是右侧按钮即时删除/撤回。

## 环境

| 项目 | 值 |
| --- | --- |
| 目标设备 | OPPO Pad Mini（OPD2515） |
| 系统 | ColorOS 16.0 / Android 16 / API 36 |
| 当前版本 | 2.2.2（versionCode 11） |
| compileSdk / targetSdk | 36 |
| minSdk | 29（Android 10） |
| 语言 | Kotlin |
| 构建 | Gradle 8.11.1 + AGP 8.9.1 + JDK 17 |
| 图片加载 | Coil 3（含视频抽帧、背景模糊） |
| 视频播放 | Media3 ExoPlayer 1.7.1 |
| 手势缩放 | PhotoView 2.3.0 |
| EXIF / 地址 | androidx.exifinterface + Android Geocoder |
| 整体取色 | androidx.palette |

应用不含 `.so` 原生库，兼容 Android 15 / 16 的 16 KB 页大小设备。

## 构建

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew assembleDebug
```

产物：

```
app/build/outputs/apk/debug/Less-v2.2.2-debug.apk
```

仓库的 `dist/` 保留每个版本：

```
dist/Less-v1.0.0-debug.apk
dist/Less-v2.0.0-debug.apk
dist/Less-v2.0.1-debug.apk
dist/Less-v2.0.2-debug.apk
dist/Less-v2.0.3-debug.apk
dist/Less-v2.0.4-debug.apk
dist/Less-v2.1.0-debug.apk
dist/Less-v2.1.1-debug.apk
dist/Less-v2.2.0-debug.apk
dist/Less-v2.2.1-debug.apk
dist/Less-v2.2.2-debug.apk
dist/SHA256SUMS.txt
```

## 安装到平板

```bash
~/Library/Android/sdk/platform-tools/adb install -r dist/Less-v2.2.2-debug.apk
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
| `ACCESS_MEDIA_LOCATION` | 读取照片 EXIF GPS，用于显示拍摄地址 |
| `READ_EXTERNAL_STORAGE` | 仅 Android 10–12 使用（`maxSdkVersion=32`） |

没有 `INTERNET`，没有 `MANAGE_EXTERNAL_STORAGE`，不绕过系统媒体库。

## 验证

- `lintDebug`：通过，无 error
- Android 16 平板模拟器（Pixel Tablet，2560×1600，API 36）：
  - 单首页三 tab 与悬浮胶囊导航
  - 照片模式三张白边扑克牌 + 模糊背景 + 底部导航同时展示
  - 点击卡片进入专注，返回后仍在照片 tab
  - 视频 tab 点击直接播放，右侧圆形收藏/删除/撤回，底部时间胶囊
  - 统计 tab 「使用统计」、双卡片、重置入口与确认框
  - 权限弹窗 Allow limited access / Allow all；授权后无需重启即可进入 tab
  - 照片专注的整页取色背景、按比例圆角图片、信息气泡、信息按钮、批量删除汇总
  - 2:3 竖屏（1680×2520）测试：方图抵满左右、横图按比例居中、上下空区使用照片色调
  - 照片缩放：整张图连同圆角一起放大，不再出现固定画框
  - 一组看完自动进入下一组
  - 首页三张卡片左右展开、倾斜叠放，圆角单线白边
  - 底部胶囊导航保持原来的 44dp 大小
  - 收藏页照片/视频、设置页

## 项目结构

```
app/src/main/java/com/kkorpse/less/
├── LessApp.kt
├── data/
│   ├── MediaItem.kt
│   ├── MediaRepository.kt
│   ├── MediaSession.kt
│   ├── BrowseSessions.kt
│   └── PhotoGroupBuilder.kt
├── prefs/
│   ├── AppPreferences.kt
│   └── BrowseStore.kt
├── ui/
│   ├── MainActivity.kt              # 单首页 tab host + 悬浮胶囊导航 + 权限
│   ├── PhotoHomeFragment.kt         # 照片模式：三张扑克牌
│   ├── PhotoFocusPage.kt
│   ├── PhotoFocusActivity.kt        # 照片专注
│   ├── PhotoFocusAdapter.kt
│   ├── PhotoFocusViewModel.kt
│   ├── BatchTrashController.kt
│   ├── VideoFeedFragment.kt         # 视频 tab
│   ├── VideoFeedAdapter.kt
│   ├── VideoFeedViewModel.kt
│   ├── StatsFragment.kt             # 使用统计 tab
│   ├── SettingsActivity.kt
│   ├── FavoritesActivity.kt
│   ├── ViewerActivity.kt            # 系统选择器兜底
│   ├── ViewerAdapter.kt
│   ├── GalleryAdapter.kt
│   ├── FocusCardLayout.kt
│   ├── FanCardView.kt               # 首页扑克牌卡片（圆角 + 白边）
│   ├── SquareFrameLayout.kt
│   └── TrashController.kt
└── util/
    ├── MediaAccess.kt
    ├── PhotoLocationResolver.kt
    ├── PhotoColorResolver.kt
    ├── Format.kt
    └── Dimens.kt
```

## 已知边界

- 系统相册的「收藏」夹没有公开写入接口，收藏目前只在 Less 内生效。
- 拍摄地址需要照片自身带 GPS，并需要 `ACCESS_MEDIA_LOCATION`；没有 GPS 或系统无法反查时显示「未记录拍摄地点」或坐标。
- 删除/撤回需要系统确认；确认框由 Android 媒体提供方绘制，可能是浅色样式。
- 「已删除」统计的是本应用发起的删除次数；在系统相册里手动删除不会计入。
- 收藏页只显示当前仍有权限访问、且未被移入回收站的内容。
- 视频流目前自动播放声音，没有静音开关。
- 图片 HDR / 10-bit 显示效果取决于系统解码与屏幕能力；视频解码取决于设备硬件解码器。
- 首次扫描大图库需要一点时间，MediaStore 对刚拷贝进来的文件可能有扫描延迟。
- 部分照片授权目前依赖系统设置管理已选内容；统计页会提示当前为部分授权。
- 整页取色使用照片主色再压暗，深色照片的背景会接近黑色，属于预期效果。

## 版本记录

见 [CHANGELOG.md](CHANGELOG.md)。
