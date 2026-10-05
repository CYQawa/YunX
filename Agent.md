# Agent.md — YunX（云析）AI 协作指南

本文件面向在本仓库工作的 AI 编码代理（Claude Code / Cursor / Copilot Agent 等）。
目标：让代理无需反复摸索即可写出**符合本项目既有约定**的代码。

**语言约定：本项目所有代码注释、UI 文案、提交信息、PR 描述统一使用中文。**

---

## 1. 项目速览

**YunX（云析）** 是一个 Android 网盘分享链接解析与高速下载应用。用户粘贴分享链接 → 浏览分享内容 → 取直链 → 分片并发下载到本地。

| 项 | 值 |
|---|---|
| 包名 | `com.yunx.app` |
| 源码根 | `app/src/main/kotlin/com/yunx/app` |
| 语言 | Kotlin |
| UI | Jetpack Compose + Material Design 3 |
| 持久化 | Room（KSP 注解处理）+ SharedPreferences |
| 网络 | OkHttp 4.12.0 |
| minSdk / targetSdk / compileSdk | 24 / 34 / 36 |
| JVM target | 17 |
| 开源协议 | GNU AGPL-3.0 |

支持平台：夸克、UC、迅雷、百度、139（和彩云）、123 云盘。

---

## 2. 目录结构与职责

```
app/src/main/kotlin/com/yunx/app/
├── MainActivity.kt              # 单 Activity 入口
├── YunXApp.kt                   # Application，全局初始化
├── （另有少量分散的完整性自检代码，见 §9，勿误判为恶意代码）
├── crash/                       # 崩溃捕获与崩溃展示页
│   ├── CrashHandler.kt
│   └── CrashActivity.kt
├── util/
│   ├── LogRedactor.kt           # ★ 日志脱敏（URL/Cookie/token 打码）
│   └── LogExporter.kt
├── data/
│   ├── network/                 # 各平台 API 封装 + 常量 + 异常
│   │   ├── {Quark,UC,Xunlei,Baidu,C139,Pan123}Api.kt
│   │   ├── {...}Constants.kt
│   │   ├── ShareLinkParser.kt   # ★ 统一分享链接识别入口
│   │   ├── HttpClients.kt       # OkHttp 客户端工厂
│   │   ├── QuarkCdn.kt / XunleiDeviceFingerprint.kt
│   │   └── model/               # DTO：ShareSession / ShareFile / DownloadLink 等
│   ├── repository/              # 业务仓库层（Account* / Resolve* 成对存在）
│   │   ├── ShareResolveRepository.kt   # ★ 解析仓库公共接口
│   │   └── {平台}{Account,Resolve}Repository.kt
│   ├── db/                      # Room：Entity + Dao + AppDatabase
│   │   ├── AppDatabase.kt       # ★ 版本号与 Migration 集中管理
│   │   ├── SecureAccountDaos.kt # ★ 凭证 Dao 加密装饰器
│   │   └── {平台}Account{Entity,Dao}.kt / DownloadTask* / Bookmark*
│   ├── download/                # 下载引擎（本项目最复杂的模块，见 §5）
│   │   ├── DownloadManager.kt   # ★ 任务调度 / 分片规划 / 断点续传
│   │   ├── ChunkDownloader.kt   # 单分片 Range 请求
│   │   ├── HlsDownloader.kt / HlsRequestPolicy.kt
│   │   ├── HttpRangePolicy.kt / DownloadPathPolicy.kt
│   │   ├── DownloadSaver.kt / DownloadService.kt（前台服务）
│   │   └── DownloadPlatform.kt  # ★ 平台标识字符串常量
│   ├── security/CredentialCipher.kt    # ★ Android Keystore 凭证加解密
│   ├── announcement/            # ★ 应用内公告（远程列表 + 本地已读记录，见 §3.35）
│   │   ├── AnnouncementApi.kt   # 公开接口客户端（列表 / 详情；列表接口有副作用，勿轮询）
│   │   ├── AnnouncementReadStore.kt  # 已读口径的唯一真源（服务端没有已读接口）
│   │   └── AnnouncementTime.kt  # UTC ISO 8601 → 本地时间（**不用 java.time**，minSdk 24）
│   ├── gopeed/GopeedEngine.kt          # ★ Gopeed 进程内引擎（导入 AAR / 加载 .so / 进程内 REST，见 §3.27、§3.28）
│   ├── backup/                  # 认证备份（口令派生密钥 + AES-GCM）
│   ├── update/UpdateChecker.kt
│   └── prefs/SettingsRepository.kt     # ★ 所有设置项的唯一入口
└── ui/
    ├── MainScreen.kt            # ★ 主容器：底部导航 + 覆盖层式二级页面
    ├── SnackbarController.kt    # ★ 全局 Snackbar 通道
    ├── navigation/MainTab.kt    # 底部 4 Tab 枚举
    ├── screens/                 # 一级/二级页面 + 各平台 Sheet
    ├── resolve/                 # 解析结果页（ShareDetailScreen 等）
    ├── login/                   # 各平台登录页
    ├── viewmodel/               # 每个功能一个 ViewModel + 内嵌 Factory
    ├── components/ items/       # 可复用小组件
    │   └── RemoteImageLoader.kt / RemoteImage.kt   # ★ 全项目唯一的网络图片加载器（OkHttp，**不用 Coil**，见 §3.35）
    └── theme/                   # Color / Type / Theme / ThemeController
```

另有两个不在 `app/src/main/kotlin` 下的关键路径：

- `app/libs/gopeed-classes.jar` —— Gopeed 的 gomobile Java 桥接（**已打字节码补丁**，见 §3.28，勿用官方原版覆盖）；
- `tools/patch-gopeed-classes.py` —— 生成上面那个 jar 的补丁脚本（**换新 AAR 时必须重跑**，见 §3.28）。

---

## 3. 必须遵守的项目约定

违反这些约定的代码即使能编译，也会与现有代码风格脱节，**请务必先读同类文件再动手**。

### 3.1 ViewModel：自定义 Factory，不用 DI 框架

项目**没有** Hilt/Koin。每个 ViewModel 内嵌一个 `Factory`，依赖由 `MainScreen.kt` 手工传入。

```kotlin
class BookmarkViewModel(private val dao: BookmarkDao) : ViewModel() {
    // ...
    class Factory(private val dao: BookmarkDao) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BookmarkViewModel(dao) as T
    }
}
```

### 3.2 用户提示：统一走全局 Snackbar

**不要**在 ViewModel 里持有 `SnackbarHostState`，也不要用 Toast。

```kotlin
import com.yunx.app.ui.SnackbarController
SnackbarController.show("已收藏到「$cat」")
```

页面侧用 `rememberGlobalSnackbarHostState()` 或 `GlobalSnackbarHost()` 渲染。
**注意**：全屏覆盖层页面会遮住 `MainScreen` 的宿主，覆盖层内需自带 `SnackbarHost`。

### 3.3 二级页面：`AnimatedVisibility` 全屏覆盖层，而非 NavHost

项目**没有** Navigation-Compose 路由表。二级页面（About / Theme / Bookmark…）的模式是：
`MainScreen` 内一个 `showXxx: Boolean` 状态 + `AnimatedVisibility` 叠加一层全屏 Composable。

```kotlin
AnimatedVisibility(
    visible = showBookmarks,
    enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.96f),
    exit  = fadeOut(tween(180)) + scaleOut(targetScale = 0.96f)
) {
    BookmarkScreen(onBack = { showBookmarks = false }, /* ... */)
}
```

覆盖层页面必须自带 `BackHandler { onBack() }`。

### 3.4 设置项：只能加在 `SettingsRepository`

所有偏好读写集中在 `data/prefs/SettingsRepository.kt`（SharedPreferences 名 `yunx_settings`）。
写法：`var` + 自定义 getter/setter，值域用 `coerceIn` 兜住，默认值放 `companion object` 常量。

```kotlin
var maxConcurrentDownloads: Int
    get() = prefs.getInt("max_concurrent_downloads", DEFAULT_MAX_CONCURRENT_DOWNLOADS)
    set(value) { prefs.edit().putInt("max_concurrent_downloads", value.coerceIn(1, 10)).apply() }
```

### 3.5 下载引擎：依赖通过 Provider 闭包注入，保证「改设置即时生效」

`DownloadManager` 不直接持有 `SettingsRepository`，而是接收 lambda：

```kotlin
threadProvider     = { platform -> settings.downloadThreadsFor(platform) }
concurrencyProvider = { settings.maxConcurrentDownloads }
speedLimitProvider  = { settings.downloadSpeedLimit }
```

新增可调参数时**沿用这个模式**，不要在构造时取快照值。

### 3.6 凭证安全：Cookie / JWT 必须加密落库

- 账号 Dao 一律经 `SecureAccountDaos.xxx(rawDao, cipher)` 装饰后使用，**不要直接用 `rawXxxAccountDao()`**。
- 下载任务的请求头（含 Cookie）经 `CredentialCipher.encrypt(json, "download.requestHeaders")` 加密。
- 打日志涉及 URL / Cookie / token 时必须过 `LogRedactor`：`LogRedactor.url(url)`。

### 3.7 Room 迁移：必须写 Migration，禁止破坏性迁移

`AppDatabase.kt` 现为 **version = 16**（v1.2.5 是 10 → v1.2.6 起 13 → 收藏快捷方式 15 → 115 支持 16）。新增表/字段的流程：

1. `entities` 数组追加 Entity
2. `version` +1
3. 新增 `abstract fun xxxDao()`
4. 写 `MIGRATION_N_N+1`（新增表用 `CREATE TABLE IF NOT EXISTS`，不动旧表）
5. 注册到 `.addMigrations(...)`

`fallbackToDestructiveMigrationFrom(1..8)` 仅适用于早期开发版；**v9 起必须保留用户凭证与下载任务**。

**已知问题（2026-10-03 线上崩溃，决定暂不修）**：在跑过库版本更高构建的设备上再装回旧 APK，磁盘里的库版本会比 APK 声明的高，
Room 走 `onDowngrade` → 没有向下的迁移路径 → `IllegalStateException: A migration from 15 to 13 was required but not found`。
崩溃点在 `app/src/main/kotlin/com/yunx/app/ui/MainScreen.kt:287` 的 `AppDatabase.get()` 首次查询开库处，异常无人捕获 ⇒ **之后每次启动都崩**，
用户只能清应用数据或装回更高的包。触发前提是「版本名与 versionCode 都相同、只有库版本不同」的两个包互装（那段时间库从 13 涨到 16 而 versionCode 一直是 11），
普通用户碰不到，只有来回装包的开发/协作者会撞上。**要修的话**：builder 加 `.fallbackToDestructiveMigrationOnDowngrade()`
（Room 2.6.1 已有该 API，降级时清库重建而非抛异常），再用 `RoomDatabase.Callback.onDestructiveMigration()` 给用户一句提示；
升级路径不受影响，仍然强制走 Migration。

### 3.8 平台标识：用 `DownloadPlatform` 常量，不要裸字符串

```kotlin
object DownloadPlatform {
    const val QUARK = "quark";  const val UC = "uc";     const val XUNLEI = "xunlei"
    const val BAIDU = "baidu";  const val C139 = "c139"; const val PAN123 = "pan123"
    const val GENERIC = "generic"   // 手动添加 / 应用自更新
}
```

### 3.9 新增平台支持时的完整清单

成对创建 `{X}Api.kt` / `{X}Constants.kt` / `{X}AccountRepository.kt` / `{X}ResolveRepository.kt`（实现 `ShareResolveRepository`）/ `{X}Account{Entity,Dao}.kt` / `{X}AccountSheet.kt` / `{X}CloudScreen.kt` / `{X}LoginScreen.kt` / `{X}AccountViewModel.kt` / `{X}CloudViewModel.kt`，并在 `ShareLinkParser`、`DownloadPlatform`、`AppDatabase` 中登记。

### 3.10 UI 依赖：material3 显式钉 alpha（Material 3 Expressive，勿"顺手"改回）

版本目录里 `androidx-material3` **自带版本号**（`material3Expressive = "1.5.0-alpha18"`），刻意覆盖 Compose BOM 给的 1.4.0：
Expressive 的主题/动效/排版/形状在 1.4.0 已稳定可用，但 ButtonGroup、SplitButton、ToggleButton、LoadingIndicator、
MaterialShapes、波浪进度条、FloatingToolbar、FAB 菜单等**组件只存在于 1.5.0-alpha** —— 这是有意取舍，不是遗漏或笔误。
升级 BOM 或该 alpha 前，必须逐条重新核对三条门槛（kotlin-stdlib 要求的 Kotlin 版本 / AAR 的 minCompileSdk ≤ compileSdk /
manifest 的 minSdk），依据与历史版本对照写在 `gradle/libs.versions.toml` 中 `material3Expressive` 上方。

**动效规格的读取位置有硬性约束**：`MaterialTheme.motionScheme` 是 `@Composable` 属性，只能在 composable 作用域读取。
要把它取出的规格传给 `AnimatedContent` 的 `transitionSpec`、`remember {}`、点击回调等**非 @Composable 的 lambda** 时，
必须先在 composable 里取到局部变量再捕获进 lambda；写在 lambda 内会报
`@Composable invocations can only happen from the context of a @Composable function`。
（`AnimatedVisibility` 的 `enter` / `exit` 参数位在 composable 参数位置求值，可直接写。）

### 3.11 minSdk 钉 24（勿降回 23）

`minSdk = 24` 是被 AGP 8.13 的 D8 缺陷逼出来的，不是随手抬的：

- `minSdk < 24` 时 D8 必须脱糖接口的静态方法：它把接口上的 `$default` 桥方法（`RowScope.weight$default`、
  `DrawScope.drawLine-…$default` 这类）搬进合成的 `Xxx$-CC` 伴生类，**却不改写第三方库（AAR）字节码里的调用点**，
  调用点仍指向原接口 ⇒ 运行时 `NoSuchMethodError: No static method weight$default(...) in class …RowScope`。
  实测证据：崩溃包 `classes15.dex` 能 dump 出悬空调用点 `invoke-static/range → RowScope.weight$default`，
  同时 `classes.dex` 里已生成 `RowScope$-CC`。触发点：mikepenz markdown 渲染 README 表格/引用块
  （`MarkdownTable.kt:106`、`MarkdownBlockQuote.kt:46`）。
- **只有未混淆的包会崩**：release 变体开 R8，R8 在 D8 之前就把桥内联掉了（实测 release APK 内
  `weight$default` / `drawLine-…$default` / `*-CC` 出现 0 次）。所以这个坑只在 debug / CI 包上暴露，
  与设备系统版本无关（Android 10 上同样崩，因为缺的是 APK 里的方法，不是系统能力）。
- 24 起系统原生支持接口的静态/默认方法，D8 不再脱糖，调用点天然成立。同类问题 Sentry 也踩到过
  （AGP 8.13 + `minSdk < 24` 的接口 `$default` 桥，见 getsentry/sentry-java#5302），他们的解法是在自己的
  调用点上显式传参绕开桥；我们改不了第三方 AAR 的字节码，所以抬 minSdk 是代价最小且确定有效的修法。

**降回 23 的前提**：确认 AGP 已修掉该 D8 脱糖缺陷（换版本后用 debug 包打开带表格的 README，实测不崩）。

### 3.12 文件名显示：统一用 `FileNameText`（别再手写 maxLines）

文件名（含文件夹名）的展示方式由用户设置决定（「主题与外观 → 文件名显示」，`SettingsRepository.fileNameMultiLine`，
内存态在 `ThemeController`），因此**展示文件名的地方一律用 `ui/components/FileNameText.kt` 的 `FileNameText`**：

```kotlin
FileNameText(text = file.fname, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
```

- 默认（`fileNameMultiLine = false`）：单行 + `basicMarquee` 跑马灯，与历史观感一致；`true` 时折行显示（最多 3 行）。
- 已接入：`ShareFileRow`（解析页 + 七个网盘页 + 转存/移动选择器的**唯一行组件**）、
  七个 `*SaveSheet`/`SaveToCloudSheet` 的文件名头、`CloudFileSheets` 文件详情头、`DownloadScreen` 的任务行/分组行。
- 不要在各调用处再写 `maxLines` / `overflow` / `basicMarquee`：写死单行会让该设置失效，写死多行则默认观感被改。
- 面包屑、对话框标题、分享标题（`BookmarkScreen`）**不**走它：它们不是文件名，横向空间紧张时折行会破坏布局。

### 3.13 文件操作弹窗：解析页与网盘页同一套（别再往行里塞图标按钮）

文件/文件夹的操作统一收进底部弹窗，行内不放操作图标（历史遗留的「转存」图标已删除）：

- **解析页**（`ui/resolve/ResolveFileActionSheet.kt`）：点击文件行弹出（下载 / 转存）；文件夹点击是进入目录，
  用行尾「更多」按钮弹出同一个弹窗（下载文件夹 / 转存）。动作链路：下载 → `checkBaiduLimit` + `fetchDownloadLink`（下载链接弹窗）；
  下载文件夹 → `ResolveViewModel.downloadFolder`（与批量下载同一条链路 `downloadFiles`，文件夹递归）；
  转存 → `ResolveViewModel.requestSave`，随后**在同一弹窗内**切到转存步骤（见下条）。
- **转存是弹窗内的二级步骤，不是第二个弹窗**（与网盘页「移动到」完全同构）：`ResolveFileActionSheet` 内部
  `private enum class ResolveActionStep { MENU, SAVE }` + `AnimatedContent(fadeIn(effectsDefault()) togetherWith fadeOut(effectsFast()))`；
  SAVE 内容由 `saveStep: @Composable (onBack, onDone) -> Unit` 插槽提供，返回箭头回主菜单。
  六个平台的目录选择器是 `ui/screens/*SaveSheet.kt` 里的 `internal fun XxxSaveContent(resolveViewModel, cloudViewModel, onBack)`
  （`SaveToCloudContent` / `UCSaveContent` / `XunleiSaveContent` / `BaiduSaveContent` / `C139SaveContent` / `Pan123SaveContent`），
  外壳统一用 `SaveStepScaffold(title, subtitle, onBack, content)`（`CloudFileSheets.kt`，内部就是带返回箭头的 `StepHeader`）——
  **不要再写 `ModalBottomSheet` + 自绘标题行**。转存成功判定：`ResolveViewModel.saveTarget` 由非空变 null（成功才清空，
  失败/未登录保留原值让用户重试），`ShareDetailScreen` 用 `LaunchedEffect(saveTarget) { if (saveTarget == null) onDone() }` 关闭整个弹窗；
  `saving` 期间禁止下滑关闭与返回菜单（避免进度提示随内容一起消失）。
- **网盘页**（`ui/screens/CloudFileSheets.kt` 的 `FileActionSheet`）：形态同源，多出分享/移动/重命名/删除（自己网盘才有的操作）。
- 顶部信息头 `FileSheetHeader` 与操作项 `ActionItem` 两个组件为两处共用 —— 改样式只改这两处，**不要再手写一份菜单行**。
- 过渡：只有「下载 / 下载文件夹」这类**要另开弹窗或直接入队**的动作才先 `sheetState.hide()` 播完退场动画再执行
  （否则弹窗会瞬间消失，并与紧接着弹出的链接弹窗叠在一起）；同弹窗内的步骤切换（转存）不关弹窗，靠 `AnimatedContent` 淡入淡出。
- `ShareFileRow` 只剩 `onClick` / `onMore` / `onLongClick`（`onSave` 参数已删）：行尾按钮只用于「点击行为被占用」的场景（文件夹点击进目录）。

### 3.14 主页快捷方式：收藏链接「添加到主页」，标记存 Room 不存设置

收藏页长按 → 「添加到主页」，被添加的收藏以网格出现在**解析页（主页）输入态下方**：

- **数据**：`BookmarkEntity.homePinned: Boolean = false`（Room 列 `homePinned INTEGER NOT NULL DEFAULT 0`，`AppDatabase` 版本 13 → 14，
  `MIGRATION_13_14` 用 `ALTER TABLE bookmark ADD COLUMN ...`）。**不要用 `SettingsRepository` 存一份 ID 列表** ——
  置顶状态属于收藏本身，存设置会出现两份状态（删除收藏时残留、顺序无法同步）；Room Flow 天然让收藏页与主页同步刷新。
  查询/写入走 `BookmarkDao.observeHomePinned()`（`WHERE homePinned = 1 ORDER BY createTime DESC`）与 `updateHomePinned(id, pinned)`，
  经 `BookmarkViewModel.homeBookmarks`（StateFlow）与 `setHomePinned(id, pinned)` 暴露。
- **UI**（`ui/screens/ResolveScreen.kt` 的 `HomeShortcutsSection` / `HomeShortcutTile`）：区块在「开始解析」按钮与错误卡片之后，
  仍在同一个 `verticalScroll` 列里；**不能用 `LazyVerticalGrid`**（外层已纵向滚动，同方向嵌套滚动会崩），
  用 `bookmarks.chunked(HOME_SHORTCUT_COLUMNS = 4)` 手写行网格，末行补 `Spacer(Modifier.weight(1f))` 保证格子等宽。
  瓦片 = 48dp 圆角色块 + `FileNameText(maxLines = 2, textAlign = Center)`；
  色块文字规则**只有一个实现**：`ResolveScreen.kt` 的 `internal fun homeTileLabel(bookmark)` = 自定义文字（`homeLabel`）
  > `title` 前 `HOME_LABEL_MAX_LENGTH = 4` 个字 > 平台简称（`platformShortLabel`，标题为空时的兜底）；
  返回空串才退回 `Icons.Outlined.Link` 图标。字号按字数自适应（≤2 字 `titleMedium` / 3 字 `labelLarge` / ≥4 字 `labelSmall`），
  保证 4 个字在 48dp 方块里放得下（`maxLines = 1` + `TextOverflow.Ellipsis` 兜底大字号）。
  自定义入口在收藏页长按菜单的「自定义图标文字」（仅 `homePinned` 时显示）：`HomeLabelDialog` 的输入框
  用 `homeTileLabel(bookmark)` 作 placeholder（复用同一规则，不要另写一份"自动文字"），
  存 `BookmarkEntity.homeLabel: String = ""`（Room 列 `homeLabel TEXT NOT NULL DEFAULT ''`，版本 14 → 15，`MIGRATION_14_15`），
  经 `BookmarkDao.updateHomeLabel` / `BookmarkViewModel.setHomeLabel` 写库；空串 = 恢复自动文字。
  空态给引导卡片（提示去收藏页添加）；标题右侧「管理」直接打开收藏页（`MainScreen` 的 `onOpenBookmarks = { showBookmarks = true }`）。
- **交互**：点击瓦片 = 直接解析（GitHub 收藏先 `GitHubLinkParser.parse` → `startGitHubResolve`，其余 `startResolve`），
  并把链接/提取码回填输入框；长按瓦片 → 确认弹窗后 `setHomePinned(id, false)` 移除（防误触）。
- **收藏页**（`ui/screens/BookmarkScreen.kt`）：长按菜单增加「添加到主页 / 从主页移除」（`onToggleHome`），行内 `homePinned` 时显示「主页」小徽标。
  `BookmarkScreen.onResolve` 与主页快捷方式都要走 GitHub 分支，GitHub 收藏（`platform = "GITHUB"`，即 `currentPlatform.name`）不能在网盘解析里被吞掉。
- `FileNameText` 增加了带默认值的 `maxLines` / `textAlign` 参数（既有调用不受影响）：需要多行/居中的场景传参，不要绕开组件自己写 `Text`。

### 3.15 叠加页容器变换（关于云析 / 支持开发 / 主题与外观 / 收藏）：源必须真的被移出组合

`MainScreen.kt` 里四个叠加页共用一套「容器变换」（Container Transform），改这条链路前先读完本节：

- **结构**：`SharedTransitionLayout` → 外层 `Box(背景 surface)` → 源 `AnimatedVisibility(visible = overlayRoute == null)`
  （主界面）与目标 `AnimatedVisibility(visible = overlayRoute != null)`（`OverlayPage`）。
  两者**互斥**，不是叠加：实测主界面常驻在下面时，叠加页里的 `Card` 底色会整片画不出来（见 `OverlayPage` KDoc）。
- **源**：谁被点，谁就是源，用 `Modifier.sharedBounds(rememberSharedContentState(KEY), animatedVisibilityScope = sourceScope)`
  加在那个元素上，并且**必须在源那侧 `AnimatedVisibility` 的 composable 作用域里构造**（`rememberSharedContentState` 是 `@Composable`）。
  设置页那三行由 `MainScreen` 建好 modifier 传下去（`SettingsScreen` 的三个 row 参数）；
  收藏页没有卡片，源就是**解析页顶栏的收藏图标**（`IconButton` 的 `modifier`，key `OVERLAY_KEY_BOOKMARKS`）。
- **目标**：`OverlayPage(modifier = Modifier.sharedBounds(rememberSharedContentState(route), animatedVisibilityScope = targetScope))`，
  `route` 取 `shownRoute`（**不是** `overlayRoute`：后者在返回瞬间就变 null，退出动画会没内容可渲染）。
  新增叠加页时不要再写 `if (route == …) Modifier else …` 这类特例，一律走 sharedBounds。
- **时长**：目标 `AnimatedVisibility` 的 `exit = fadeOut(tween(300))` 必须 ≥ bounds 形变时长（默认弹簧约 300ms），
  否则退出一结束内容就被移出组合，回收形变被截断，观感像"没做动画"。
- CSS 式的"共享元素"在这里就是同一把 key 的两侧修饰符；key 定义在 `MainScreen.kt` 的 `internal const val OVERLAY_KEY_*`。

### 3.16 深色模式字体发黑：全屏页必须有 `Surface`（`LocalContentColor` 默认是黑色）

**症状**：深色模式下个别文字仍是黑色（历史案例：引导页第 1 页「云析」、第 2 页「使用前请阅读」）。
**根因**：`LocalContentColor` 的默认值是 `Color.Black`，**只有 `Surface` / `Scaffold`（以及 Button、Card 这类自绘容器）才会把它设成
`contentColorFor(底色)`**（如 `surface → onSurface`）。页面只要不在这些容器里，`Text` 不写 `color` 就是黑字 ——
浅色模式看不出来，深色模式立刻暴露。

**本项目的雷区**（都是「手动铺底色」、绕开了 Scaffold 的地方）：
- `ui/screens/OnboardingScreen.kt`：`MainScreen.kt` 里 `if (showOnboarding) { OnboardingScreen(...); return }` 的提前返回全屏覆盖页；
  已用 `Surface(modifier = modifier.fillMaxSize(), color = colorScheme.surface)` 包住整页（底色与 `BlobBackground` 的 base 一致，观感不变），
  第 1 页「云析」与第 2 页「使用前请阅读」另外显式写了 `color = colorScheme.onSurface`。
- `ui/MainScreen.kt` 的**横屏分支**：手动 `Row(NavigationRail + 内容)`，原先只有 `Box.background(background)`；
  同样已换成 `Surface(color = colorScheme.background)` 包住内容与 Snackbar（此前横屏 + 深色模式下，列表里没写 color 的文件名会是黑字）。
- 其它页面（登录页 / 关于 / 支持 / 主题 / 收藏 / 各 Tab 页）都有 `Scaffold`，或本身就是 `AlertDialog` / `ModalBottomSheet`（自带 Surface），不受影响。

**约定**：
1. 新增「全屏覆盖页 / 手动布局页」时用 `Surface` 铺底，不要用 `Modifier.background(...)`；确实只能用 `background` 时，页内每个 `Text` 都要显式给 `color`。
2. 深色模式自查重点看**大标题**这类没写 `color` 的文本（最容易漏）。
3. 排查手段：`grep -rn "Color(0x\|Color.White\|Color.Black" app/src/main/kotlin/com/yunx/app`（正常只应命中 `ui/theme/Color.kt` 的方案令牌）。

---

### 3.17 权限申请统一收口在引导页第 3 页（**别再往业务页面加"每次启动都弹"的检查**）

**现状**：引导页第 3 页（`ui/screens/OnboardingPermissionPage.kt` 的 `PermissionPage(storageGranted, storageDenied, onRequestStorage)`）一次性过三件事：
| 卡片 | 权限/设置 | 可申请的系统版本 | 入口 |
| --- | --- | --- | --- |
| 通知权限 | `POST_NOTIFICATIONS` | Android 13+ 可申请；低版本/被系统关闭 → 跳系统设置 | `PermissionState.canRequestNotifications()` → 申请，否则 `openNotificationSettings()` |
| 后台运行 | 「忽略电池优化」白名单 | 全版本（系统设置页） | `PermissionState.requestIgnoreBatteryOptimizations()` |
| 存储权限 | `WRITE_EXTERNAL_STORAGE` | **仅 Android 9 及以下**需要；10+ 走媒体库无需授权 | `PermissionState.storagePermissionRequired()` |

**能否跳过**：通知与后台运行可跳过（只影响提醒 / 息屏存活）；**存储权限在 Android 9 及以下是必要权限，不给跳过** ——
没有它 `DownloadManager` 会在保存前直接抛「未授予存储权限，无法保存到下载目录」（`data/download/DownloadManager.kt` 的 HLS 分支与分片合并分支各一处），
所以存储权限状态提升在 `ui/screens/OnboardingScreen.kt`（`storageGranted` / `storageDenied` / `requestStorage` / `storageBlocking`）：
未授权时底部 `OnboardingBottomBar(finishEnabled = !storageBlocking, ...)` 把「开始使用」按钮**置灰禁用**（`Button(enabled = false)`），
**文案与图标恒为「开始使用」+ Check，不随状态改字**（用户明确要求：改文案会让人以为按钮变成了别的东西）；
授权入口是权限页的「存储权限」卡片，授权成功返回后 `ON_RESUME` 重查 ⇒ 按钮自动恢复可点。
卡片按钮在拒绝过一次后由「授权」改成「去设置授权」并走 `PermissionState.openAppDetails()`
（避免授权框已被「不再询问」吞掉、点了没反应）。Android 10+ 的 `storageGranted()` 恒为 true ⇒ 这套阻塞逻辑完全不生效。

**卡片按钮显示规则**：`PermissionCard` 内部只要 `granted == true` 就不渲染操作按钮 ——「去设置」只在真的需要用户动手时才出现，别在调用处补 `if`。

**唯一状态入口**：`app/src/main/kotlin/com/yunx/app/util/PermissionState.kt`。
`Build.VERSION.SDK_INT` 的分支只允许写在这个文件里（13+ 的运行时通知权限、9- 的存储权限、各系统设置 Intent 的兜底跳转都在里面），调用处不要再自己判断版本。

**已从业务页面移除**（历史行为，勿恢复）：
- `MainActivity.kt`：启动时的通知权限申请 + 「通知权限」引导弹窗（`notificationPermLauncher` / `showNotificationGuide` / `NotificationPermissionDialog`）。
- `ui/MainScreen.kt`：首次下载任务启动时弹的「保持后台下载」电池优化 `AlertDialog`（`showBatteryGuide` / 监听 `downloadViewModel.tasks` 的 `LaunchedEffect`）。

**保留的兜底**（有意为之，不冲突：只有用户真的用到该功能且权限缺失时才提示）：
- `ui/MainScreen.kt` 的 `storagePermissionLauncher` + `downloadManager.storagePermissionProvider`（保存前兜底，Android 9- 才真会弹）。
- `ui/screens/DownloadScreen.kt`：手动添加下载任务入口的存储权限兜底。
- `ui/screens/SupportScreen.kt`：保存图片时的存储权限兜底。
- `ui/screens/SettingsScreen.kt`：通知状态展示与手动申请、「通知栏下载进度」开关点击时申请、电池优化手动入口（设置页是用户主动去改的地方，不算打扰）。

**注意**：权限授权框和系统设置页返回都会触发 `ON_RESUME`，所以引导页第 3 页的卡片状态用 `DisposableEffect(lifecycleOwner)` + `LifecycleEventObserver` 重查，
不能只用 `remember { mutableStateOf(...) }` 的初值（否则会出现"授权完返回，卡片还显示未授权"）。

---

### 3.18 发布签名：CI 用 GitHub Secrets，本地构建未签名（**别把证书提交进仓库**）

**证书在哪**：仓库 Settings → Secrets and variables → Actions 的四个 Secrets ——
`KEYSTORE_BASE64`（.jks 的 base64 文本）、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

**Gradle 侧**（`app/build.gradle.kts` 顶部）：读四个环境变量
（`YUNX_KEYSTORE_FILE` / `YUNX_KEYSTORE_PASSWORD` / `YUNX_KEY_ALIAS` / `YUNX_KEY_PASSWORD`），
**四项齐备且证书文件真实存在**才 `create("release")` 注册签名配置；否则 release 的 `signingConfig = null`
⇒ 产物叫 `app-release-unsigned.apk`。
所以本地与 `ci.yml` 不带变量构建时，`assembleRelease` 照样成功，只是不签名；本地想出自签名包，自己导出这四个变量再构建。
`debug` 变体不受影响，仍用仓库里的 `debug.keystore`。

**CI 侧**（`.github/workflows/build-apk.yml` nightly）：构建前多一步 `Restore release keystore` ——
把 `secrets.KEYSTORE_BASE64` 解码到 `$RUNNER_TEMP/yunx-release.jks`、用 `keytool -list` 预校验口令与别名、
把路径写进 `$GITHUB_ENV` 的 `YUNX_KEYSTORE_FILE`；口令/别名/密钥口令只注入 `Build release APK` 那一步。
构建后用 `apksigner verify --print-certs` 自检产物确实带正式证书 —— **签名没生效就让 CI 红，而不是发出一堆装不上的包**。
`ci.yml` 故意不注入这些变量：它只做编译校验，上传的也只有 debug 包。

**装包注意**：
- 换签名后，之前用 debug 签名装的包（含旧 nightly）与正式签名**互不兼容**，必须先卸载再装，否则 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
- nightly 与正式版同签名 ⇒ 可直接覆盖安装升级（这也是给测试者的便利）。
- `.gitignore` 已挡 `*.jks` / `*.keystore` / `keystore.properties`（`!debug.keystore` 例外）。
- 生成 `KEYSTORE_BASE64`：Linux/macOS `base64 -w 0 你的.jks`（macOS 若报 `-w` 不支持就用 `base64 -i 你的.jks`），Windows PowerShell `[Convert]::ToBase64String([IO.File]::ReadAllBytes("你的.jks"))`。

### 3.19 游客模式：列目录不要求登录；夸克/UC 小文件也能直接下载（**别再往列目录加登录拦截**）

**结论**：解析分享**不再要求登录**。7 个网盘的分享**列表**接口都允许匿名访问（用户实测：浏览器未登录也能列出文件）；
**夸克 / UC 的分享直链本身也不校验登录态**，未登录可直接下载（夸克约 50MB 以内、UC 实测 4GB 都放行）；
其余平台的**取直链/转存**都要账号，所以登录闸门只保留在这些入口。

**各平台列目录的匿名能力（实测 + 源码核对）**：

| 平台 | 列目录 | 依据 |
|------|--------|------|
| 123 | 匿名 | `Pan123Api.getShareFiles`（`/b/api/share/get`）无鉴权头、注释「匿名、无签名」；`fidToken = S3KeyFlag\|Etag\|StorageNode` 已随列表返回 |
| 139 | 匿名 | `C139Api.getShareFiles` 走 `sharePostAnonymous`，body `account:""`、无 authorization/mcloud-sign |
| 百度 | 匿名 | 公共分享（无提取码）时 `sekey=""`、省略 `&sekey=`；仓库层无登录前置检查 |
| 夸克 / UC | 匿名 | API 层无 cookie 预检；仓库/VM 也不再有闸门。**下载也匿名**（见下节「游客下载」） |
| 迅雷 | 匿名 | `XunleiApi.getShare` / `getShareDetail` 在 token 为空时**不写 Authorization 头**（带上失效 Bearer 反而被判 `unauthenticated`） |
| 115 | 匿名 | `Pan115Api.getSharePage`（`/share/snap`）不带 Cookie 也能列目录；但 `/share/downurl` 要分享者 `user_id`，**下载仍要求登录**（`Pan115ResolveRepository` 未覆写 `getGuestShareDownloadLink`，走接口默认失败） |
| GitHub | —— | 本来就不需要登录 |

**闸门在哪（`ResolveViewModel`）**：
- `startResolve` / `openFolder` / `goBack`：空凭据**照常下传**，并置 `isGuest = credential.isBlank()`（`backToInput` / `startGitHubResolve` 复位 false）。
  解析失败时给服务端原文 + 一句「当前未登录，可到「网盘」页登录 XX 后重试」。
- 仍要求登录（不要动）：`saveToCloud`、`batchSaveToCloud`、`requestSave`（转存：游客直接提示并 return，不打开目录选择）。
- 允许游客（仅夸克/UC，判据是 `supportsGuestDownload()`）：`fetchDownloadLink`（走 `getGuestShareDownloadLink`）、`downloadFiles`/`batchDownload`、`startDownload`。
  其余平台在这三处仍是闸门，提示语统一为「下载/转存需要先登录 X（未登录仅能浏览文件列表）」，走 `downloadError` → Snackbar。

**游客下载（夸克 / UC，2025 实现）**：
- 取链：`ShareResolveRepository.getGuestShareDownloadLink(session, file)`（接口默认实现=失败，只有夸克/UC 覆写）
  → `QuarkApi/UCApi.getGuestShareDownloadLink(...)`：**不建临时目录、不转存**，直接打各自的 `file/download` 分享端点，
  body 带 `fids` / `fids_token`(=`ShareFile.fidToken`) / `pwd_id`(=`session.shareId`) / `stoken`(=`session.stoken`)；
  夸克还要 `speedup_session:""` + `token:""`（`token` 是社交转存令牌，游客取不到，官方前端同样 catch 后退化成空串）。
- **关键：`__pugs`**。服务端随取链响应 `Set-Cookie` 下发游客态 `__pugs`（3 小时有效，Domain 分别为 quark.cn / uc.cn），
  它是**下载直链必须带的 Cookie**：夸克缺它 CDN 412，UC 缺它 403（`RequestDeniedByCallback: require login [auth not found]`）。
  代码在 `QuarkApi/UCApi` 里用 `pugsFromSetCookies()` 取出**本次响应**的值 → 写进 `DownloadLink.guestCookie`（`DownloadLink` 末尾新增字段，默认空串=登录态）。
  绑定粒度是响应级：**必须用同一次响应的 `__pugs`**，所以取值先存局部变量再写缓存；`guestPugs` 只是给同批次的后续请求当请求侧 Cookie。
- 下载头：`enqueueDownload` 判 `link.guestCookie.isNotBlank()` 走游客头 ——
  UC = `Cookie: __pugs` + `UCConstants.GUEST_UA`（uc-cloud-drive/2.5.20 …）+ `Sec-Ch-Ua` + Referer/Origin；
  夸克 = `Cookie: __pugs` + `QuarkConstants.API_USER_AGENT` + Referer。夸克直链仍走 `QuarkCdn.fastest(url, guestCookie)`。
- 大小上限：夸克约 **50MB**（超出报 `23018`，提示「超出游客可获取的大小上限…请先登录」）；UC 实测无此限制（4GB 也放行）。
- 错误码文案：夸克 `23018`/`31001`；UC `31001`/`23018`/`14001`（分享失效或提取码错）/`41020`（令牌失效）。
- 转存依旧要登录：游客点「转存」仍走登录闸门（夸克/UC 的转存都要账号态）。
- **115 复用 `DownloadLink.guestCookie` 装另一种东西**：不是游客 Cookie，而是取链响应 `Set-Cookie` 下发的
  **900 秒、path 绑定该文件的 CDN 下载 Cookie**（登录态也要带，缺它 CDN 403 `no cookie value`），且必须与登录 Cookie 合并成
  `Cookie: <登录 Cookie>; <CDN Cookie>`——见 §3.25 硬规则 3。所以「`guestCookie` 非空」在 115 不代表游客。

**UI**：`ShareDetailScreen` 的 `GuestBrowseNotice(viewModel.sharePlatform)`（`isGuest` 时显示在标题/面包屑下方）按平台给文案 ——
夸克「可直接下载约 50MB 以内的小文件」、UC「可直接下载（不限大小）」、其余「可查看文件列表，下载/转存需先到「网盘」页登录」；
操作弹窗里点「转存」会先关弹窗再弹 Snackbar（否则提示被 `ModalBottomSheet` 挡住）。`ResolveViewModel.sharePlatform` 是 `currentPlatform` 的只读出口。

**迅雷专属实现**（唯一需要改请求构造的平台）：
- `XunleiApi.panRequest` / `panRequestM`：`accessToken` 为空 ⇒ 不写 `Authorization`（`currentAccessToken` 的旧值不会漏进来）。
- `XunleiApi.panCall(..., anonymous = true)`：不带验证码、失败也不刷新 token / 不重试 `captcha_invalid`，把服务端真实错误直接抛上来。
- `XunleiResolveRepository.accessOrEmpty()`：未登录返回 `""`；`deviceIdOrGuest()`：未登录回退 `XunleiApi.newDeviceId()`（否则「缺少设备标识」会把匿名列目录挡在门外，且该 id 只在进程内复用、不落库）。
- 转存/取直链仍走 `access()` ⇒ 游客点下载会看到「请先登录迅雷网盘」。

**排错提示**：
- 服务端拒绝时优先看文案里的 `HTTP xxx` / `errno`：百度 `-6` = 未登录或登录态失效（此时提示「需要提取码，或需要登录百度网盘」），夸克/UC 非 JSON 响应会带 `HTTP 401/403`。
- 游客模式下「某些平台列不出来」不代表协议不行：多数是分享本身需要提取码（先输密码再判断），或风控限速。
- 回退：把 `startResolve` / `openFolder` / `goBack` 的空凭据下传换回「凭据为空即报错」，并恢复各仓库的 `isNullOrBlank` 校验即可；UI 提示条随 `isGuest` 自动消失。
- 回退游客**下载**（只想要「游客仅能浏览」）：删掉 `fetchDownloadLink` / `downloadFiles` / `startDownload` 里的 `supportsGuestDownload()` 分支（恢复成「空凭据即报错」），
  再删 `ShareResolveRepository.getGuestShareDownloadLink` 默认方法与两个仓库覆写、`QuarkApi/UCApi.getGuestShareDownloadLink` 及 `__pugs` 相关私有方法/字段、
  `DownloadLink.guestCookie`、`UCConstants.GUEST_UA`/`GUEST_SEC_CH_UA`，`enqueueDownload` 恢复成只认登录 Cookie。
- 游客下载失败先分辨是哪一步：取链阶段失败看错误码文案（23018 大小超限 / 31001 需登录）；取链成功但下载 403/412 说明 `__pugs` 没带上或用了别的响应的值（检查 `DownloadLink.guestCookie` 是否为当次响应值）。

---

### 3.20 分享有效期：UI 中性码必须经 `ShareExpire` 转换（**别把中性码直接下发给接口**）

**中性码只有一套**：`1`=永久有效、`2`=1 天、`3`=7 天、`4`=30 天，定义在
`app/src/main/kotlin/com/yunx/app/data/network/model/ShareExpire.kt`（`FOREVER` / `ONE_DAY` / `SEVEN_DAYS` / `THIRTY_DAYS`）。
有效期选择器与结果展示都在 `app/src/main/kotlin/com/yunx/app/ui/screens/CloudFileSheets.kt`
（`expireOptions` 选项、`expireLabel()` 文案），各网盘页 `onShare = { _, passcode, expiredType -> ... }` 下发的就是这个码。

**各平台 `createShare` 的有效期语义完全不同**（这就是 139/百度/123 三个平台「选永久建成 1 天、选 1/7/30 天显示永久」的原因）：

| 平台 | 接口字段 | 真实语义 | 转换函数 |
|---|---|---|---|
| 139（`C139Api.createShare`） | `period` | 天数；**永久 = 完全不传该字段** | `ShareExpire.daysOrNull()`（返回 `null` 即不传） |
| 百度（`BaiduApi.createShare`） | `period` | 字面天数 `0/1/7/30`；`0` = 永久 | `ShareExpire.baiduPeriod()` |
| 123（`Pan123Api.createShare`） | `expiration` | 绝对 ISO 时间串（now + 天数）；永久 = 2099 哨兵 | `ShareExpire.daysOrNull()` 后交给 `expiration()` 拼串 |
| 迅雷（`XunleiApi.createShare`） | `expiration_days` | **字符串** `"-1"/"1"/"7"/"30"`；`-1` = 永久 | `ShareExpire.xunleiDays()` |
| 夸克 / UC（`QuarkApi` / `UCApi`） | `expired_type` | 取值恰好等于中性码，原值直传 | 无（`QuarkApi` / `UCApi` KDoc 已注明） |
| 115（`Pan115Api.createShare`） | `share_duration` | **字符串** `"-1"/"1"/"3"/"7"/"15"`；`-1` = 永久；**有效期只在 `/share/updateshare` 里给**（创建接口没有该字段） | `ShareExpire.pan115Duration()` |

**115 是唯一的例外：它的档位（永久/1/3/7/15 天）与中性码语义冲突**（中性码 `3` 是 7 天，115 的 `3` 是 3 天），所以另开一段专用码位
`PAN115_FOREVER=101` / `PAN115_ONE_DAY=102` / `PAN115_THREE_DAYS=103` / `PAN115_SEVEN_DAYS=104` / `PAN115_FIFTEEN_DAYS=105`
与选项表 `ShareExpire.PAN115_OPTIONS`，由 `Pan115CloudScreen` 通过 `FileActionSheet`/`BatchActionSheet` 的 `expireOptions` 参数传入
（默认值 `defaultExpireOptions` 仍是其它平台的 永久/1/7/30 天）。**别把 115 的档位并回中性码**，那会让「3 天」静默变成「7 天」。

**选择器的两个坑（都在 `CloudFileSheets.kt` 的 `ShareStep` 里，2026-10 修过一次）**：
① 默认选中项必须取 `expireOptions.firstOrNull()?.second`，**不能写死 `ShareExpire.FOREVER`**——各平台的第一档都是「永久有效」，所以取值等价，
但 115 的档位码是 101..105，写死中性码 `1` 会让弹窗一打开五个档位一个都没选中。
② 档位行用 `FlowRow` + 横竖都 `spacedBy(8.dp)`，**不能退回单行 `Row`**——115 的五档一行放不下，`Row` 不会换行、只会把最右边的「15 天」压扁。

**转换必须在 ViewModel 层完成**，`api.createShare(...)` 只接受平台真实语义（各 API 的 KDoc 都写了「不是 UI 中性码」）。新增平台或改有效期选项时，
只要走 `ShareExpire` 就不会再错位；`ShareExpire.daysOrNull()` 对未知码**抛异常**（fail-loud），不允许再用 `else -> 永久 / 30 天 / "-1"` 兜底——
那会把「新加了一种有效期但忘了映射」静默变成另一种有效期，比报错更难查。

**回填显示**：接口不返回有效期的平台（139/123/迅雷）用**用户所选的中性码**回填 `ShareInfo.expiredType`；
百度用响应里的 `expiredType`（`BaiduApi.BaiduShareResult.expiredType`，字段缺失为 `null`，回退到用户所选值，**别用 0 兜底——0 是永久**）。
认不出的值统一回填 `ShareExpire.UNKNOWN = 0`，`expireLabel()` 显示「未知」，不再 fail-open 成「永久有效」。
夸克/UC 用 `optInt("expired_type")` 取值，字段缺失同样落到「未知」。

**回退**：删掉 `ShareExpire.kt` 并在各 ViewModel 恢复「中性码直传 + `else -> 1`」即可回到旧行为（不推荐，bug 会复现）。

---

### 3.21 「网盘更新」按钮：能自动化就直接下载，不能才回落解析页

检查更新弹窗里的「网盘更新」不再无条件跳解析页：入口是 `app/src/main/kotlin/com/yunx/app/ui/MainScreen.kt` 的
`onNetdiskUpdate = { link -> ...; resolveViewModel.startUpdateDownload(link) }`，决策逻辑全在
`app/src/main/kotlin/com/yunx/app/ui/viewmodel/ResolveViewModel.kt` 的 `startUpdateDownload()` 里：

| 情况 | 行为 |
|---|---|
| GitHub 链接 / 无法识别的链接 | 回落解析页（`fallbackToResolve()`） |
| 对应网盘**已登录** | 自动「创建会话 → 列目录（`collectShareFolder`，最多 12 层）→ 取体积最大的 `.apk` → 转存/取直链 → 入队下载」 |
| **未登录**且是夸克 | 同样自动匿名取直链；`.apk` 超过 `QUARK_GUEST_MAX_BYTES`（`50L * 1024 * 1024`）时回落解析页并提示先登录 |
| **未登录**且是 UC | 自动匿名取直链下载（UC 无大小限制） |
| 其他网盘未登录 / 分享需要提取码 / 找不到 `.apk` / 取链失败 | 回落解析页（失败原因写进 `downloadError`，由 `ResolveScreen` 弹 Snackbar） |

- 自动化成功 → `downloadStarted = true`，复用 `MainScreen` 既有的 `LaunchedEffect` 自动切到「下载」Tab；
  回落 → `updateFallbackToResolve = true`，由新增的 `LaunchedEffect` 切回「解析」Tab（用完调 `consumeUpdateFallbackToResolve()`）。
- 夸克/UC 已登录时先取 `getFreshCookie()`：会话、列目录、取链、下载四处必须用**同一份** cookie（同 §3.19 的 `__puus` 约束）。
- 更新包筛选：所有 `.apk`（忽略大小写）里取体积最大的一个；分享里没有 `.apk` 就回落，不会顺手下别的文件。
- **回退**：把 `onNetdiskUpdate` 改回 `currentTab = MainTab.Resolve; resolveViewModel.startResolve(link, null)` 即可
  （`startUpdateDownload()` / `fallbackToResolve()` / `updateFallbackToResolve` 可一并删除）。

---

### 3.22 「自动识别剪贴板」开关（关掉后应用完全不读剪贴板）

设置页「通用」组第一项是该开关：持久化在 `app/src/main/kotlin/com/yunx/app/data/prefs/SettingsRepository.kt` 的
`clipboardSuggestEnabled`（键 `clipboard_suggest_enabled`，默认 `true`），内存态在
`app/src/main/kotlin/com/yunx/app/ui/theme/ThemeController.kt` 的 `clipboardSuggestEnabled`（Compose 可观察，所以关掉即时生效）。

全应用**唯一**读取剪贴板的位置是 `app/src/main/kotlin/com/yunx/app/ui/screens/ResolveScreen.kt` 的 `readClipboardSafely()`，
它只被 `maybeSuggestClipboard()` 调用，而 `maybeSuggestClipboard()` 只有两个触发点，二者在开关关闭时都不工作：
- `DisposableEffect(lifecycleOwner, clipboard, clipboardSuggestEnabled)`：关闭时把 `clipboardSuggestion` 置空并 `onDispose {}`，
  不注册 `OnPrimaryClipChangedListener`、不注册 `ON_RESUME` observer（连冷启动那一次检测也不执行）；
- Android 11- 的 2 秒轮询 `LaunchedEffect(clipboardSuggestEnabled)`：关闭时直接 `return@LaunchedEffect`。

**新增任何剪贴板读取点前，必须先判断 `ThemeController.clipboardSuggestEnabled`**；
写入剪贴板（复制直链 / 复制 Cookie）不受该开关影响。

---

### 3.23 「接受预发布版更新」开关（检查更新是否包含 GitHub Pre-release）

设置页「通用」组的「检查更新」下面一项即该开关。持久化在
`app/src/main/kotlin/com/yunx/app/data/prefs/SettingsRepository.kt` 的 `acceptPrereleaseUpdate`
（键 `accept_prerelease_update`，默认 `false` = 只收正式版），内存态在 `ThemeController.acceptPrereleaseUpdate`。

**两条通道**都在 `app/src/main/kotlin/com/yunx/app/data/update/UpdateChecker.kt`：

| 通道 | 端点 | 说明 |
| --- | --- | --- |
| 正式版（默认） | `RELEASES_LATEST_URL` = `/repos/CYQawa/YunX/releases/latest` | GitHub 只会返回最新的**非** Pre-release、**非** Draft 版本 |
| 预发布（开关打开） | `RELEASES_LIST_URL` = `/repos/CYQawa/YunX/releases` | 返回数组、按发布时间倒序；取第一条 `draft == false` 且 `tag_name` 非空的版本，因此**可能命中 Pre-release** |

- 统一入口 `UpdateChecker.fetchLatestRelease(includePrerelease: Boolean = false)`；`fetchBody()` 负责 HTTP 与错误文案（403/429 限流提示、404「仓库暂无 Release」），`parseRelease()` 负责解析并回填 `Release.prerelease`。
- 两个调用点都在 `app/src/main/kotlin/com/yunx/app/ui/MainScreen.kt`（启动检查 + 手动检查），都传 `ThemeController.acceptPrereleaseUpdate`；**切换开关后不会自动重查**，下一次手动检查（或重启后的启动检查）才生效。
- `app/src/main/kotlin/com/yunx/app/ui/screens/UpdateSheet.kt` 在版本号后面加了一枚「预发布」小标签（`release.prerelease == true` 时），避免用户不知情地装上测试包。

**版本比较规则（`UpdateChecker.compareVersions`，改动过，注意别改回去）**：先逐段比数字前缀；数字段完全相同时，
只有**两边都带后缀**才继续比后缀（先比后缀里的第一段数字，`1.3.0-beta2 > 1.3.0-beta1`，再按字符串比）；
**只有一边带后缀时视为相等** —— 这是为了保住 fork 构建 `1.2.6-gh1` 不被判成比同号正式版旧的既有约定。
因此：**预发布版的 `versionName` 必须带上同样的后缀**（例如 `1.3.0-beta1`），否则同一数字段的两个预发布版
（beta1 → beta2）会被判成「已是最新版本」。

**回退**：删掉设置项与 `acceptPrereleaseUpdate`（`SettingsRepository` / `ThemeController`）、两个调用点的实参、
`UpdateSheet` 的「预发布」标签，以及 `RELEASES_LIST_URL` / `requestReleaseList()` / `BodyResult`（`fetchLatestRelease` 恢复无参）。

---

### 3.24 对外联系方式（QQ 群 / GitHub 仓库）：常量集中在 `AppLinks`

QQ 群号与仓库地址**只允许**写在 `app/src/main/kotlin/com/yunx/app/util/AppLinks.kt`，界面里引用常量：

| 常量 | 值 | 用在哪 |
| --- | --- | --- |
| `AppLinks.QQ_GROUP` | `635207650` | 引导页首页胶囊、设置页「关于」组 |
| `AppLinks.GITHUB_REPO` | `https://github.com/CYQawa/YunX` | 引导页 / 关于页卡片跳转、设置页「关于」组 |
| `AppLinks.GITHUB_REPO_DISPLAY` | `github.com/CYQawa/YunX` | 界面文字展示（不带协议头） |

- 入口清单：`app/src/main/kotlin/com/yunx/app/ui/screens/OnboardingScreen.kt` 的 `QQGroupPill`（欢迎页 GitHub 卡片下方，点击拉起 QQ 群卡片）；
  `app/src/main/kotlin/com/yunx/app/ui/screens/SettingsScreen.kt` 的「关于」组最后两项 —— 「QQ 交流群」（点击拉起 QQ 群卡片，长按复制群号）、
  「GitHub 仓库」（系统浏览器打开，没有可用浏览器时退化为复制链接）。同组分段圆角随之变为
  `FIRST`（关于云析）→ `MIDDLE`（支持开发）→ `MIDDLE`（QQ 交流群）→ `LAST`（GitHub 仓库）。
- **QQ 入口 = 先跳 QQ、失败再复制**（需求：能直接进群就别让人手动复制）：
  `AppLinks.qqGroupScheme()` 拼 `mqqapi://card/show_pslcard?src_type=internal&version=1&uin=<群号>&card_type=group&source=qrcode`，
  `AppLinks.openQQGroup(context)` 负责 `startActivity` 并返回是否成功；返回 false 时调用方 `copyToClipboard(群号)` + 提示「未安装 QQ，群号已复制」。
  - **为什么光有群号只能走 mqqapi**：`https://qm.qq.com/cgi-bin/qm/qr?k=...` 那种加群链接需要在 QQ 群后台申请 key，群号拼不出来。
  - **为什么不用 `resolveActivity()` 预检**：Android 11+ 的软件包可见性会在未声明 `<queries>` 时让它返回 null，把「装了 QQ」误判成「没装」；
    隐式 Intent 直接 `startActivity` 不受该限制，所以靠捕获 `ActivityNotFoundException` 判断（`runCatching`），也就不必往 AndroidManifest 加 `<queries>`。
- **两处反馈方式不同不是笔误**：引导页是 `MainScreen` early-return 的全屏页、不在 `Scaffold` 里，`SnackbarController`
  的气泡挂不到它上面 ⇒ 引导页用系统 `Toast`（Android 13+ 复制剪贴板时系统还会自己弹提示）；设置页在 `Scaffold` 内 ⇒ 用 `SnackbarController`。
- 写剪贴板**不受**「自动识别剪贴板」开关约束（§3.22 管的是读取）。
- 引导页欢迎页的 `Column` 带 `verticalScroll`：小屏（≈640dp 高）上「图标 + 标题 + 标签 + GitHub 卡片 + QQ 胶囊」会超出一屏；
  `fillMaxSize()` 会把它带成 `minHeight` 约束，所以内容不足一屏时 `Arrangement.Center` 仍然居中，超出时才滚动。
- 换群号 / 换仓库地址时**只改 `AppLinks`**（`AboutScreen` 的 GitHub 卡片也已改为引用常量），别再往界面里写死。
- **回退**：删掉引导页 `QQGroupPill` 与设置页两项即可；只想退回「点击复制」行为的话，把两处 `onClick` 换回
  `copyToClipboard(...)`、删掉 `openQQGroup`/`qqGroupScheme` 即可（`AppLinks` 常量本身没有副作用）。

---

### 3.25 115 网盘接入（Cookie 网页登录 / 分享解析 / 转存 / 创建分享）

115 是第 7 个网盘，**认证方式与夸克/UC/百度/139 同为「网页登录取 Cookie」，但请求契约与字段规则自成一派**，
接口依据 `/storage/emulated/0/云析分析资料/网盘API文档/115/115_api.md`（个人盘）与 `115_share_api.md`（分享）。

**接口契约（`Pan115Api`）**

| 项 | 值 |
| --- | --- |
| 个人盘 host | `https://webapi.115.com`（`Pan115Constants.API_BASE`） |
| 必备请求头 | 桌面 Chrome UA（`Pan115Constants.WEB_UA`）+ `X-Requested-With: XMLHttpRequest` + `Referer: https://115.com/`；**带 Cookie 时 `X-Requested-With` 不能省** |
| 成功判定 | 顶层 `state == true`；错误码在 `errno` / `errNo` / `error`，提示文案在 `msg`（`Pan115Constants` 已收口常用码） |
| 个人盘列目录 | `GET /files?aid=1&cid=&show_dir=1&offset=&limit=` → 顶层 `data[]` + `count` + `path[]` |
| 空间 | `GET /files/index_info?count_space_nums=1` → `data.space_info.all_total.size` / `all_use.size` |
| 分享列目录 | `GET /share/snap?share_code=&receive_code=&cid=&offset=&limit=&format=json` → `data.userinfo` / `data.shareinfo` / `data.list[]` |
| 分享直链 | `GET /share/downurl?dl=1&user_id=&share_code=&file_id=[&receive_code=]` → `data.file_url_302`（优先）或 `data.file_url` |
| 个人盘直链 | **电脑端接口** `POST https://proapi.115.com/app/chrome/downurl?t=<unix秒>`（表单 `data=<Pan115Crypto 加密的 {"pickcode":…}>`）→ 解密后 `url.url`；兜底 `GET /files/download?pickcode=` → `data.file_url`（pickcode 就是列表项的 `pc`） |
| 转存 | `POST /share/receive {share_code, receive_code, file_id, cid}`（`cid=0` 为根） |
| 创建分享 | `POST /user/allow_protocol {action:pubshare}` → `POST /share/send {user_id, file_ids, ignore_warn}` → `POST /share/updateshare {share_code, share_duration}` |
| 删除 / 重命名 / 移动 | `POST /rb/delete`（回收站，不是 `/files/delete`）/ `POST /files/batch_rename`（**不是** `/files/edit`）/ `POST /files/move` + `GET /files/move_progress?move_proid=` 轮询 |

**七条与其它平台不同的硬规则（改动前必读）**

1. **分享直链要「分享者」的 `user_id`，不是自己的 UID**，而它只出现在 `share/snap` 的 `data.userinfo.user_id` 里。
   `ShareSession` 只有 `shareId/stoken/title` 三个字段，所以分享者 UID 与提取码被编码进 `stoken`：
   `Pan115Constants.encodeShareToken(receiveCode, userId) = "<receive_code>|<user_id>"`，用时 `decodeShareToken()` 拆回来。
   **别往 `ShareSession` 加字段**——它是 7 个平台共用的契约，加字段要动所有仓库。
2. **列表项的目录判定是「反的」**：文件夹项**没有 `fid`**、自身 ID 在 `cid` 里；文件项 `fid` = 自身 ID、`cid` = 父目录；
   分享列表则用 `fc == 0` 判目录、目录 ID 取 `cid`、文件 ID 取 `fid`。`Pan115Api.parsePersonalFiles/parseShareFiles` 已把
   `ShareFile.fid` 归一成「可直接下发给接口的 ID」（文件夹就是它的 `cid`），因此 `share/send` 的 `file_ids`、`files/move` 的 `fid[0]`、
   `rb/delete` 的 `fid[0]` 都能直接用 `ShareFile.fid`，**不要再按平台分叉判一次目录**。个人盘文件的 `fidToken` 存的是 `pc`（pickcode），分享文件没有 pickcode（`fidToken` 为空串）。
3. **网页端接口的直链要「双 Cookie」**：`files/download` 与 `share/downurl` 的响应会 `Set-Cookie` 下发一个 **900 秒、path 绑定该 object 的 CDN Cookie**，
   下载时必须把「登录 Cookie + 这个 CDN Cookie」一起带上，缺了就 CDN 403 `no cookie value`。
   CDN Cookie 经 `DownloadLink.guestCookie` 一路传到 `ResolveViewModel.enqueueDownload`，在那里 `mergeCookies(credential, guestCookie)`
   合成 `Cookie` 头，并补 `User-Agent`（客户端 UA）与 `Referer`（`https://115.com/`）。**取链与下载必须紧邻**，CDN Cookie 会过期。
   电脑端接口（见规则 5）**同样下发 CDN Cookie**（2026-10-03 实测：不带它直链 CDN 返回 403 `no cookie value`，带上就是 206 + `Content-Range`），
   所以 `getAppDownloadLink()` 用 `postFormWithCookie()` 取响应 Cookie 填进 `guestCookie`，下载侧照旧合并登录 Cookie。`downloadCookie()` 会优先挑带非根 `path` 的那条（同一响应里可能还带 WAF 的 `acw_tc`）。
4. **创建分享的有效期不在创建接口里**：`/share/send` 建完必须再调 `/share/updateshare {share_duration}`，
   否则选了「长期」也会落成**默认 15 天**。`share_duration` 取值 `-1/1/3/5/7/15`（字符串），见 §3.20 的 115 行与专属码位 `101..105`。
   首次创建前还要过 `/user/allow_protocol {action:pubshare}`（进程内 `@Volatile protocolAllowed` 缓存，失败也放行，真创建时服务端会再判）。
5. **网页端取链接口拿不到大文件直链，必须走「电脑端」加密接口**（2026-10-03 线上两轮抓包 `/storage/emulated/0/抓包/bug/115/` 与 `bug/115/2/` 定位）：
   浏览器 UA 下 `/files/download` 报 `50028 文件大小超出限制，请使用115电脑端下载`（同账号 23B 小文件正常、1.05GB 被拒），
   分享 `/share/downurl` 报 `50029 当前版本过低，请升级到最新版本下载`；**换成客户端 UA 后 482MB 仍报 50028、分享仍报 50029 —— 改 UA 无效**。
   真正的电脑端协议是 `https://proapi.115.com/app/chrome/downurl`：POST 查询串 `?t=<unix秒>`，表单 `data=<Pan115Crypto.encode("{\"pickcode\":…}", key)>`，
   响应 `{state, data:"<base64>"}` 用**同一个 key** `Pan115Crypto.decode` 解开，取第一个带 `url` 对象的条目的 `file_name / pick_code / url.url`。
   算法已与两份参考实现逐字节核对一致（`云析分析资料/网盘参考/115drive-webdav-main/115/crypto.go` 的 `Encode/Decode/xorDeriveKey/xorTransform/rsaEncrypt/rsaDecrypt`、
   `115-minus-main/src/platform/115/download-codec.ts`）：16 随机字节 key（随请求加密给服务端，无需预共享）→ `key‖xor(deriveKey(key,4))‖reverse‖xor(LONG_KEY)` → base64(RSA PKCS1v15，明文分块 117)。
   代码里 `Pan115Api.getAppDownloadLink()` 优先、网页端 `/files/download` 仅作兜底。
   **已用抓包里的账号 Cookie 真机联调验证通过**（2026-10-03）：proapi 返回 `state:true` + `data` 密文，`Pan115Crypto.decode` 解出
   `file_name=ATMOS-碟中谍5（中字）：对白+枪声.m2ts`、`size=482052096`、`url` 前缀 `https://cdnfhn307.115cdn.net/...`（正是网页端报 50028 的那个文件），
   带登录 Cookie + CDN Cookie 后 `Range: bytes=0-1023` 拿到 `206 Content-Range: bytes 0-1023/482052096`。
6. **客户端 UA 的版本号必须动态取，不能写死**：115 按版本校验（过低报 `50029`，alist 默认的 `27.0.5.7` 已过旧）。
   `Pan115Constants.CLIENT_UA` 是 `@Volatile var`（非 const），由 `Pan115Api.refreshClientVersion()`（进程内只拉一次）从
   `https://appversion.115.com/1/web/1.0/api/chrome` 取 `data.win.version_code`（当前 `36.0.1`）后经 `applyClientVersion()` 刷新；
   `getDownloadLink` / `getWebDownloadLink` / `getShareDownloadLink` 三处取链前都会调它。拉取失败保留兜底值 `CLIENT_UA_VERSION_FALLBACK`，不影响流程。
   **注意**：分享 `share/downurl` 的 `50029` 与 UA 版本无关 —— 换 Chrome UA 与 `115Browser/36.0.1` 重放抓包请求都返回同样的 50029，
   所以分享大文件只能靠规则 7 的「转存 + 电脑端接口」。日后若又报 50028/50029：先核对 `Pan115Crypto.kt` 常量是否仍与参考实现一致，
   再确认 `appversion` 接口是否改版（`CLIENT_UA` 是否刷新成功）。
7. **分享大文件要「先转存到自己网盘，再取电脑端直链」**：分享侧没有已验证的 proapi 等价接口，所以
   `Pan115ResolveRepository.getShareDownloadLink()` 的顺序是「先试 `share/downurl`」→ 失败且**已登录**时走 `downloadViaTempTransfer()`：
   `ensureTempDir()` 在根目录建 `YunX临时转存/tr_<nanoTime>` → `share/receive` 转存 → 轮询列目录认领新 `fid`（同名会变 `xxx(1).后缀`）
   → `getAppDownloadLink()` 取链，并把临时目录 cid 放进 `DownloadLink.cleanupDirFid`；下载完成、失败或弹窗关闭时由 `ResolveViewModel`
   调 `currentRepo().cleanupTempDir()` 删除。未登录时直接抛原始错误（提示先到「网盘」页登录 115）。
   **这条路径会在用户网盘里留下临时目录，改动时务必保证清理链路不被破坏。**

**其它已收口的坑**：`share/receive` **不回传新文件 id**（只有 `pid` 与文件/目录计数），
`Pan115ResolveRepository.transferFile` 的做法是「转存 → 重新列目标目录 → 按文件名认领新 `fid`」（同名会变成 `xxx(1).txt`，所以是「新出现的那一项」而不是精确同名）；
`/rb/delete` 返回 `800007` 表示需要二次确认，`Pan115Api.delete` 会补 `ignore_warn=1` 重试一次，`990009` 表示上一个任务未完成；
分享访问码**由服务端生成、不可自定义也不可取消**（自定义报 `4100032`、取消报 `4100034`），所以 UI 用 `PasscodeMode.SERVER_GENERATED`，
结果弹窗展示服务端回的 `receive_code`（与 139 同规则）；`share/snap` 在**未登录**下也能列目录（游客模式可浏览，下载/转存要求登录）。

**文件索引**

| 新增 | 作用 |
| --- | --- |
| `app/src/main/kotlin/com/yunx/app/data/network/Pan115Constants.kt` | host/端点/UA/错误码/Cookie 工具（`extractCookies`/`isValidCookie`/`mergeCookies`/`encodeShareToken`） |
| `app/src/main/kotlin/com/yunx/app/data/network/Pan115Api.kt` | 全部 115 接口（列目录/取链/增删改/分享/转存/创建分享） |
| `app/src/main/kotlin/com/yunx/app/data/network/Pan115Crypto.kt` | 电脑端取链协议的 RSA+XOR 编解码（§3.25 规则 5，改这里前先与参考实现核对） |
| `app/src/main/kotlin/com/yunx/app/data/repository/Pan115ResolveRepository.kt` | 分享解析：snap 列目录（分页）+ downurl 取链 + **转存后回查新 fid** |
| `app/src/main/kotlin/com/yunx/app/data/repository/Pan115AccountRepository.kt` | Cookie 落库（`/user/info` 校验并取脱敏手机号当昵称）、退出清 CookieManager/WebStorage |
| `app/src/main/kotlin/com/yunx/app/data/db/Pan115AccountEntity.kt` / `Pan115AccountDao.kt` | `pan115_account` 表（单行 id=`pan115`，Cookie 加密存，见 `SecureAccountDaos.pan115`） |
| `app/src/main/kotlin/com/yunx/app/ui/viewmodel/Pan115CloudViewModel.kt` / `Pan115AccountViewModel.kt` | 个人盘浏览（分页/递归收集/下载/重命名/移动/删除/分享）与账号态 |
| `app/src/main/kotlin/com/yunx/app/ui/login/Pan115LoginScreen.kt` | WebView 登录 `https://115.com/`，自动检测 Cookie（要求 UID + SEID）或手动粘贴 |
| `app/src/main/kotlin/com/yunx/app/ui/screens/Pan115CloudScreen.kt` / `Pan115AccountSheet.kt` / `Pan115SaveSheet.kt` | 云盘页（`expireOptions = ShareExpire.PAN115_OPTIONS`、`PasscodeMode.SERVER_GENERATED`）、账号弹窗、转存目录选择 |

改动：`ShareLinkParser.kt`（`SharePlatform.PAN115` + 三条 115 正则：`115cdn?/rc?.com/s/<sw…>`、口令 `<code>-<pwd>`、`?password=`）、
`DownloadPlatform.kt`（`PAN115`，线程设置自动多一项）、`ShareExpire.kt`（115 专属码位与 `pan115Duration()`）、
`AppDatabase.kt`（**version 16 + `MIGRATION_15_16`** 建 `pan115_account`）、`CloudFileSheets.kt`（`expireOptions` 参数化 + 新码位文案 + 结果弹窗认 115）、
`ResolveViewModel.kt`（平台分派与下载头）、`ResolveScreen.kt` / `ShareDetailScreen.kt` / `DriveScreen.kt` / `MainScreen.kt` /
`DriveQuotaViewModel.kt` / `SettingsScreen.kt` / `BookmarkScreen.kt` / `AboutScreen.kt` / `OnboardingScreen.kt`（「7 大网盘」）/ `AuthBackupManager.kt`（备份含 115）。

**回退**：删掉上表新增文件，还原 `SharePlatform`/`DownloadPlatform`/`ShareExpire`/`AppDatabase`（版本号别回退：回退会让磁盘上的库版本比 APK 新，已升级设备会崩在 Room 的降级校验上，见 §3.7）、
各 `MainScreen`/`DriveScreen` 接线与 `AuthBackupManager` 的 115 参数即可。

---

### 3.26 网盘页「+」新建菜单：创建文件夹（7 家）/ 上传文件（占位）

**入口只有一个**：七家个人盘页顶栏「放大镜」图标**右侧**的 `CloudAddMenu`（`+` → 下拉菜单，两项：创建文件夹 / 上传文件），
位置在每页 `if (!viewModel.multiSelectMode)` 的顶栏分支里，所以**多选态自动隐藏**；
**分享浏览页（`ShareDetailScreen`）不挂这个入口**——各家文档都写明建目录/上传只在个人盘模式可用（115/百度/夸克/UC/迅雷/123/139 一致）。

**共享组件都在 `app/src/main/kotlin/com/yunx/app/ui/screens/CloudFileSheets.kt`**：
`CloudAddMenu(onCreateFolder, onUploadFile)`、`CreateFolderDialog(onDismiss, onConfirm)`、`cloudNameError(name)`（名称校验，唯一实现）。
七个云盘页（`CloudDriveScreen`/`UCCoudScreen`/`XunleiCloudScreen`/`BaiduCloudScreen`/`C139CloudScreen`/`Pan123CloudScreen`/`Pan115CloudScreen`）
各插三处：`var showCreateFolder`、顶栏 `CloudAddMenu(...)`、末尾 `if (showCreateFolder) CreateFolderDialog(...)`。

**上传项是占位**：`onUploadFile` 默认 `null`，此时菜单项置灰、文案显示为「上传文件（开发中）」；上传协议落地后把回调传进来即可，不用改组件。
**别把 `onUploadFile` 传成空 lambda**（那会让菜单项可点但什么都不发生）。

**各平台建目录接口与根目录约定**（根目录取值必须与列目录一致，否则建到别处）：

| 平台 | 调用 | 根目录 | 备注 |
|---|---|---|---|
| 夸克 | `QuarkApi.createFolder(name, parentFid, cookie)` | `"0"` | 已有接口，本次才接 UI |
| UC | `UCApi.createFolder(name, parentFid, cookie)` | `"0"` | 同上 |
| 迅雷 | `XunleiApi.createFolder(name, parentId, token, deviceId, captcha)` | `""`（空串） | 同上；`kind=drive#folder` |
| 百度 | `BaiduApi.createDir(绝对路径, cookie)` | `"/"` | **按路径**不是 fid；根目录不能拼成 `//名字` |
| 115 | `Pan115Api.createDir(pid, name, cookie)` | `Pan115Constants.ROOT_CID`（`"0"`） | 已有接口 |
| 139 | `C139Api.createDir(parentFileId, name, cookie)` | `"/"` | **本次新增**：`POST /hcy/file/create`，`type='folder'`、`fileRenameMode='force_rename'` |
| 123 | `Pan123Api.createDir(parentFileId, name, token)` | `"0"` | **本次新增**：复用上传预创建 `POST /b/api/file/upload_request`（`type=1`、`size=0`、`etag=""`、`NotReuse=true`） |

调用点统一在各页 ViewModel 的 `createFolder(name)`：成功 → `cloudMessage` + `reloadCurrent()`，失败 → `cloudMessage`（走既有 Snackbar 通道）；
创建期间复用各页既有的 `isOperating`「处理中」弹窗，所以 `CreateFolderDialog` 确认后立刻关闭（不做二次 loading 态）。

**名称校验规则**（`cloudNameError`）：非空、不是 `.`/`..`、不含 `/` `\` 与控制字符、≤255 字符。
这是文档里唯一给出的一套客户端规则（115 `cloudFileName`、123/139 的同类校验）；**服务端长度上限/保留字各家都没写**。

**已知文档缺口（实现时按「服务端报错就提示」处理，别自行兜底）**：各平台建目录都**没有错误码表**（重名是报错、自动改名还是建副本，7 家全未定义——
139 传了 `force_rename`、123 传了 `duplicate=1`，实际效果都未实测）；建完目录后**是否立即可见**也没有一家给了一致性约定（只有夸克/UC 的重命名有 `_awaitVisible`）。
123 的 `data.Info.FileId → data.FileId → data.fileId` 与 139 的 `data.fileId` 都取自逆向文档，**首次真机验证要盯这两家**。

**回退**：删掉 `CloudAddMenu`/`CreateFolderDialog`/`cloudNameError` 与七页的接线、七页 ViewModel 的 `createFolder`、
`C139Api.createDir`/`Pan123Api.createDir` 及其两条 URL 常量即可（无数据库改动）。

---

### 3.27 内置 Gopeed 下载引擎（导入 AAR · 进程内嵌 · 验证入口）

**这一批只做可行性验证**：设置页多一行入口 → 导入 AAR → 加载 .so → 启动引擎 → 建一个测试任务 → 看进度 / 暂停 / 继续 / 删除。
**现有 `DownloadManager` / `DownloadService` / 下载页链路一行没动**（§5 的机制全部照旧），验证通过再谈让 Gopeed 接管。

**为什么是「一半编译期 + 一半运行时」**：AAR 里的 Java 桥接 `classes.jar` 只有 12 KB，作为**编译期依赖**进仓库
（`app/libs/gopeed-classes.jar`，由 `tools/patch-gopeed-classes.py` 从官方 AAR 生成并打了 1 处字节码补丁，见 §3.28；
`app/build.gradle.kts` 里 `implementation(files("libs/gopeed-classes.jar"))`），
这样 gomobile 生成的类型签名保持原版；而 `jni/arm64-v8a/libgojni.so` 有 **56 MB**（静态链接整个 Go 运行时），
**不进仓库也不进 APK**，由用户在设置页导入 AAR 后运行时解出并 `System.load`。
**不做 DexClassLoader / 运行时 dex**（省掉类加载器命名空间问题）。

**AAR 事实**（`libgopeed-arm64-v8a.aar`，24.9 MB）：package `go.libgopeed.gojni`，minSdk 21，只有
`AndroidManifest.xml` + `classes.jar` + `jni/arm64-v8a/libgojni.so` + `proguard.txt`（内容恰为下面两条 keep）+ 空 R.txt/res。
ELF 的 4 个 LOAD 段 `p_align=0x1000`(4096) ⇒ **不满足 16 KB 页对齐**，将来 Android 15+ 的 16 KB 页设备要重新用
gomobile 编（`-ldflags="-extldflags=-Wl,-z,max-page-size=16384"`）；本机是 4096，不受影响。
只带 arm64-v8a：其它 ABI 导入时会报「这个 AAR 里没有 jni/&lt;abi&gt;/libgojni.so」。

**★ 加载顺序是硬约束**：必须**先 `System.load(<绝对路径>)` 成功，再触碰任何 `go.*` / `com.gopeed.*` 类**。
桥接类的静态初始化一旦抛错，JVM 会把**该类的初始化失败永久记住**：同一进程里再点只会得到
`NoClassDefFoundError`（真机第 2 次点击的报错就是这个），只能杀进程重开。
`GopeedEngine.loadLibrary()` 先 `System.load(绝对路径)`，仅当它失败才回退 `System.loadLibrary("gojni")`
（回退用于「.so 打进 jniLibs」那条兜底路线），两条原始错误文本都会保留并 `Log.e`。
**兜底方案**：把 .so 放进 `app/src/main/jniLibs/arm64-v8a/`（AGP 会自动打进 APK，无需改 gradle），
即可走系统 nativeLibraryDir 正常加载。补丁与真机失败的完整因果见 §3.28。

**R8 必须保留**（`app/proguard-rules.pro`，与 AAR 自带 proguard.txt 一字不差）：
`-keep class go.** { *; }` 与 `-keep class com.gopeed.** { *; }`——.so 是用 `FindClass` 反查这些类名的，
release 混淆后名字一变就崩。

**引擎调用方式**：`Libgopeed.start(cfgJson)` 返回端口（Go 侧出错时 native 抛 `go.Universe$proxyerror`，
`getMessage()` 就是 Go 的 error 文本）；`Libgopeed.invokeAsync(method, path, query, body, requestID, listener)`
走 **`rest.Dispatch` 进程内路由**，等价于 Gopeed 的 HTTP API 但**不开 TCP 端口**（配置里 `apiEnable=false` ⇒
不监听、也不需要 apiToken）；`stop()` 内部上限 3 秒，超时未完成的任务以错误回调收尾。
本批**没用** `subscribeTaskEvents`（进度用 1 秒轮询，够验证用）。

**用到的路由 / 报文**（信封统一 `{code,msg,data}`，`CodeOk=0`）：`POST /api/v1/tasks`（建任务，body
`{"req":{"url":"…"},"opts":{"path":"…"}}`，`data` = 任务 id）、`PUT /api/v1/tasks/{id}/pause`、
`PUT /api/v1/tasks/{id}/continue`、`DELETE /api/v1/tasks/{id}`、`GET /api/v1/tasks/{id}/status`、
`GET /api/v1/info`（`data.version`）。任务状态词：`ready/running/wait/pause/error/done`。

**启动配置**（`GopeedEngine.buildConfig`，字段名对照 `pkg/rest/model/server.go`）：`storage="bolt"`、
`storageDir`/`tempDir` 在 `filesDir/gopeed/{store,tmp}/`（目录要以分隔符结尾）、`apiEnable=false`、
`refreshInterval=500`、`downloadConfig{downloadDir, maxRunning=1, autoStartTasks=false}`。
注意 `autoStartTasks` 只管「启动时是否恢复未完成任务」，**新建任务照常立即开始**。

**下载目录**：引擎只能按**真实文件系统路径**写（写不了 SAF 的 `content://` 目录），现在优先用公共的
`Download/YunX`，权限不到位时自动退回应用外部私有目录 —— 完整规则、权限矩阵与 B 路线决策见 §3.29。

**文件与接线**：`app/src/main/kotlin/com/yunx/app/data/gopeed/GopeedEngine.kt`（`object`，`State{NOT_INSTALLED,
INSTALLED,RUNNING}`、`installFromAar`/`uninstall`/`start`/`stop`/`invoke`/`taskStatus`/`createTask`/`pauseTask`/
`continueTask`/`deleteTask`/`engineVersion`）、`app/src/main/kotlin/com/yunx/app/ui/screens/GopeedScreen.kt`（验证页：
状态卡 / 测试下载卡 / 当前任务卡 / **下载目录真实文件列表**——最后一卡是用文件系统交叉验证"确实落盘了"）；
入口 = `SettingsScreen` 新增参数 `onGopeedClick` + 「下载引擎」分组一行，`MainScreen` 新增
`OVERLAY_KEY_GOPEED` / `showGopeed` / 路由（普通淡入，不做共享元素形变）。
页面里所有引擎调用都在 `Dispatchers.IO`（引擎方法会阻塞），UI 侧统一走 `action{}` 抢 `busy` 并把异常原文显示出来。

**验证期已知边界**（都不是 bug）：引擎不随退后台保活（没做前台服务）；引擎任务**只在这个页面**可见，
不会进 YunX 下载页（两套任务库不互通）；`System.load` 解出的 .so 占 56 MB 内部存储；重复导入同一个 .so
在本进程内不会重新加载（提示需重启应用）。

**回退**：删 `data/gopeed/GopeedEngine.kt` 与 `ui/screens/GopeedScreen.kt`、`app/libs/gopeed-classes.jar`
及 build.gradle 里那行 `implementation(files(...))`、proguard 里那两条 keep、`SettingsScreen` 的
`onGopeedClick` 参数与「下载引擎」分组、`MainScreen` 的 `OVERLAY_KEY_GOPEED`/`showGopeed`/路由/传参即可（无数据库改动）。

---

### 3.28 Gopeed 启动失败根因：`libgojni.so` 没有 DT_SONAME（**别把这条 loadLibrary 补丁回退掉**）

**真机现象**（用户第一次点「启动引擎」，Android 10 / arm64-v8a）：

```
第 1 次：dalvik.system.PathClassLoader[DexPathList[[zip file "/data/app/com.yunx.app-…/base.apk"],
        nativeLibraryDirectories=[…/lib/arm64, …/base.apk!/lib/arm64-v8a, /system/lib64, /system/product/lib64]]]
        couldn't find "libgojni.so"
第 2 次：com.gopeed.libgopeed.Libgopeed        （即 NoClassDefFoundError 的 message）
```

**根因（已坐实，不是权限问题也不是路径问题）**：AAR 里的 `libgojni.so` **没有 `DT_SONAME`**——实测其 dynamic 段
只有 `DT_NEEDED = ['liblog.so','libandroid.so','libm.so','libdl.so','libc.so']`，`DT_SONAME` **缺失**。
Android linker 只按 soname 认「已经加载过的库」，所以：

1. `GopeedEngine.start()` 里的 `System.load(绝对路径)` **其实成功了**（⇒ 从应用私有目录 `filesDir` dlopen 这条路是通的）；
2. 紧接着 `Libgopeed` 类初始化 → gomobile 生成的 `go.Seq.<clinit>` 里写死 `System.loadLibrary("gojni")`；
   linker 既无 soname 可匹配、APK 的 `lib/arm64` 里也没有这个文件 ⇒ 抛 `UnsatisfiedLinkError`，
   报错前缀就是那句 `PathClassLoader[DexPathList[…]]`（`loadLibrary` 独有的格式，与第 1 条一字不差）；
3. 该失败发生在**类初始化**里 ⇒ JVM 永久记住 ⇒ 第 2 次点击变成 `NoClassDefFoundError`。

**修复 = 5 字节字节码补丁**：`go/Seq.class` 的 `<clinit>` 里那条 `ldc "gojni"`(2 B) + `invokestatic
java/lang/System.loadLibrary`(3 B) 原地写成 **5 个 `0x00`(nop)**。字节码总长度不变 ⇒ 所有偏移、异常表、
StackMapTable 都不受影响（`max_stack` 只会变小，仍合法）。引擎库改由 `GopeedEngine.loadLibrary()` 在触碰
任何 `go.*` / `com.gopeed.*` 类之前 `System.load(绝对路径)` 加载（加载顺序见 §3.27 的硬约束）。
全 jar（18 个 class）扫描确认**只有 `go/Seq.class` 这一处** `System.loadLibrary`。

**生成方式（可复现，仓库内自带脚本）**：

```bash
python3 tools/patch-gopeed-classes.py <libgopeed-<abi>.aar | classes.jar> app/libs/gopeed-classes.jar
```

`tools/patch-gopeed-classes.py` 是自包含脚本（只用标准库）：输入 AAR 时自动取其中的 `classes.jar`，
只改 `go/Seq.class` 一个条目，**要求恰好命中 1 处**否则报错退出，改完还会重新反汇编复核残留为 0；
实测输出：位置 = class 文件偏移 2190、原字节 `12 5C B8 00 5E`、实际变化 4 字节（第 4 字节本来就是 `0x00`）。
**换新的 AAR（重新用 gomobile 打包）时必须重新跑一遍这个脚本并提交新的 jar。**

**已否决的方案**（别再试，理由都在这里）：① 重写整个 `go/Seq.java` 源码替换（成员含 native 方法，签名差一点就崩）；
② 自定义 `PathClassLoader(librarySearchPath=…)`（无 soname ⇒ 会把 56 MB 的 Go runtime 再加载一份）；
③ 给 .so 补 `DT_SONAME`（`.dynamic` 里没有空槽、`dynstr` 偏移脆弱）；④ 把 .so 打进 `jniLibs`（可行但 APK 涨 56 MB，
只作兜底）。

**日志（用户要求，已加）**：`GopeedEngine` 全链路 `Log.d`/`Log.e` 打点——导入（abi、目标路径、AAR 条目列表）、
加载（`System.load` 成功/失败原文、`loadLibrary` 回退结果）、启动（启动配置 JSON、`Libgopeed.start` 结果）、
停止、`invoke`（方法/路径/body、超时、引擎返回原文）。抓取：`adb logcat -s GopeedEngine`。
**日志里的 URL / body 必须经 `util/LogRedactor`（`url()` / `line()`）脱敏后再打**（§2 的脱敏规范），别直接打原始 URL。
失败路径统一附带 `LOAD_HINT`（① `couldn't find "libgojni.so"` ⇒ jar 不是打过补丁的版本；② `NoClassDefFoundError`
⇒ 桥接类在本进程已初始化失败，必须杀掉应用重开）。验证页也据此显示「杀掉应用重开」的提示，报错文本用
`SelectionContainer` 包住方便整段复制。

**真机操作提醒**：桥接类初始化失败后，**必须杀掉应用（最近任务划掉 / 强行停止）再重开**，否则同一进程里再点
只会一直得到 `NoClassDefFoundError`。

---

### 3.29 内置引擎的落盘与权限（B 路线：申请「所有文件访问」）+ 两套下载器并存的决策

**用户拍板的三个方向（2026-10-04，别再自行改回去）**：

1. **落盘走 B 路线**：申请「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`），让引擎直接写**公共目录**；
2. **内核手动导入**：APK 不打包 56 MB 的 .so，继续由用户在设置页导入 AAR；**后续再做「应用内从 GitHub 下载 AAR」**；
3. **两套下载器并存**：老的 `DownloadManager` 分片下载器与 Gopeed 引擎同时在仓库里，**应用内可切换**（切换 UI 待做）。

**为什么不是「所有安卓版本都要所有文件访问」**（引擎只能按真实路径写文件，权限按版本分三档）：

| 系统 | 写公共目录要什么 | 代码里怎么判断 |
|---|---|---|
| Android 9- | 运行时 `WRITE_EXTERNAL_STORAGE` | `PermissionState.engineStoragePermissionPending()` |
| Android 10 | 同一个运行时权限 + manifest 的 `requestLegacyExternalStorage="true"` 回到旧模式 | 同上（Q **也要**申请，别以为 10 就不用了） |
| Android 11+ | `MANAGE_EXTERNAL_STORAGE`（「所有文件访问」），**只能用户去系统设置手动开** | `PermissionState.allFilesAccessRequired()/allFilesAccessGranted()` |

Android 10+ 原本那套「保存到公共目录」走 MediaStore/SAF，**不需要任何存储权限**——
所以 `PermissionState.storageGranted()` 在 10+ 恒为 true，**那三个方法不要改**，它们是给 MediaStore/SAF 路径用的；
引擎这条真实路径的判断是新增的 `allFilesAccessRequired/allFilesAccessGranted/engineStoragePermissionPending`。
（引导页 `OnboardingScreen` 只在 Android 9- 申请 `WRITE_EXTERNAL_STORAGE`，所以 **Android 10 上大概率还没授权**，
引擎页必须自己补申请入口。）

**目录解析**（`GopeedEngine.resolveDownloadDir(context)`）：**自定义目录**（`SettingsRepository.engineDownloadDir`，
见 §3.33）→ `StorageDirs.defaultDownloadDir()`（公共 `Download` 根目录，与内置下载器同一默认口径）→
`Android/data/<包名>/files/gopeed`；**可写性用「试写探针」判断**——建目录 + 写一个 `.yunx_write_probe` 再删掉，
成功才用该目录，否则 `Log.e` 后退回下一档。不按系统版本推断权限是因为各 ROM 对 legacy / 分区存储的处理并不一致。
（**§3.33 起默认目录不再是 `Download/YunX`**：`PUBLIC_DIR_NAME` 已删除，默认就是 `Download` 根目录。）

**UI**（`GopeedScreen`）：状态卡显示当前生效的下载目录 + 存储权限三态提示（未授权/已就绪）+ 一个按钮
（Android 11+ 跳「所有文件访问」页；10- 弹运行时授权框）；`LocalLifecycleOwner` + ON_RESUME 刷新，
从系统设置返回后目录会自动切换（`remember(allFilesReady, legacyStorageReady)` 重新解析）。
**权限是给引擎启动时写进配置的默认目录用的，但每个任务的 `opts.path` 才是真正落盘位置**，
所以授权后不需要重启引擎，下一个任务就用新目录。

**为什么不用 MediaStore 直写绕开权限**：MediaStore 只支持顺序流式写，而 Gopeed 是多连接随机写（seek + 分片），
天然不兼容；要零权限就只能「引擎下到私有目录 → 完成后复制到公共目录」，那是 A 路线（已完成评估，未采用）。

**已核实、接管时可以直接用的引擎能力**（读的是 Gopeed 源码 `pkg/protocol/http/model.go`）：
`req.extra.header`（`map[string]string`，网盘直链要的 UA/Referer/Cookie 都能带）、
`opts.extra.connections`（分片并发，正好映射现有「按网盘分别设置分片并发数」）、
`Request.Labels`（可塞 YunX 侧任务 id 做映射）。
**缺口**：`DownloaderStoreConfig` 里没有限速字段，现有「速度限制」设置项无法平移；重试由引擎自己管。

**风险提示**：`MANAGE_EXTERNAL_STORAGE` 属于特殊权限，Google Play 需要申报用途、
F-Droid 侧也可能触发审核（本项目已有 fastlane 元数据）；如果哪天因为分发渠道要放弃它，
退回 A 路线（下到私有目录后复制）即可，代码里只影响 `resolveDownloadDir` 与权限 UI 两处。

---

### 3.30 两套下载器并存：设置项切换 + Gopeed 任务映射进本地库（DB v17）

**开关**：`SettingsRepository.downloadEngine`（`ENGINE_BUILTIN` 默认 / `ENGINE_GOPEED`），设置页「下载引擎」分组第一行可切；
取值非法时按内置处理。**默认永远偏向内置下载器**——引擎是可选增强，设置项本身不能把下载功能弄坏。

**分流点只有一个**：`DownloadManager.enqueue()` 末尾的 `start(id, headers)` 之前。
任务登记（`dao.insert`）、请求头保存、`taskCallbacks`、`taskSizes` 两条路完全一致，只是「谁来执行」不同：

```
if (shouldUseEngine(platform)) startViaEngine(...) else start(id, headers)
```

`shouldUseEngine()` = 平台不是 GitHub（GitHub 走镜像回退，引擎不支持）**且** 设置选了引擎 **且** `GopeedEngine.isInstalled()`。
放这里的好处是**全部 20+ 个调用点（解析页 / 网盘页 / 下载页手动添加）自动生效，一行都不用改**。
⚠️ 走到引擎分支时若建任务失败，**按失败任务落库、errorMsg 写引擎原文，不静默回退内置下载器**——
用户明确选了引擎，悄悄换下载器比报错更难排查。

**引擎任务 ID 进本地库（DB v17）**：`DownloadTaskEntity.engineTaskId`（空串 = 内置下载器），
`MIGRATION_16_17` 就是一句 `ALTER TABLE download_task ADD COLUMN engineTaskId TEXT NOT NULL DEFAULT ''`。
**留一列而不是另建表**，是为了让下载页、暂停/继续/删除、完成清理全部复用现有逻辑，UI 零改动。
（v17 是升版本，装新包会走迁移；但**别把旧 APK 装回已升到 17 的设备**，会撞 Room 降级校验，见 §3.7。）

**进度同步**（`startEngineSync`，`init{}` 里就会拉起一次）：每 700ms 拉一次 `dao.listSyncableEngineTasks()`
（`engineTaskId != ''` 且状态不是完成/失败），逐个调 `GopeedEngine.taskStatus()` 回写本地记录：
`done` → `dao.complete` + 触发 `taskCallbacks`（夸克转存清理等回调照常）；`error` → 失败落库；
`pause` → 已暂停；其余 → `updateProgress`。没有可同步任务时协程自己 `return`，下次建引擎任务再拉起。
引擎调用走的是 `invokeAsync` **进程内分发**（不是 HTTP），所以 700ms 的频率没有网络开销。
每轮开头会确保引擎在运行：进程重启后引擎 bolt 里可能还有任务，它们通常是 pause 状态，被回写成「已暂停」由用户决定是否继续。

**操作转发**（关键：`start` 必须拦截，否则内置下载器会用同一个 URL 重复下载）：

| 操作 | 处理 |
|---|---|
| `start(id)` | 命中 `taskEngineIds` 就**提前 return**，改为转发 `continueTask` |
| `pause(id)` | **不提前 return**：转发引擎暂停后继续走原逻辑（本地状态置「已暂停」、没有分片文件所以清理是空操作） |
| `remove(id)` | 同上：额外转发 `deleteTask`，本地删记录/删文件的逻辑完全复用 |

`taskEngineIds` 是内存索引（`ConcurrentHashMap<Long, String>`），同步循环每轮从 DB 补齐；
`pause/remove` 里先用 `remove(id)` 取值（拿不到就说明不是引擎任务，行为与以前完全一致）。

**速度显示（引擎任务也有）**：同步循环把引擎 `TaskRuntimeStatus.speed` 写进 `_stats`，剩余时间按
`(total - downloaded) / speed` 自己算，分片数用 `threadProvider(platform)`（就是提交任务时给引擎的 connections），
所以下载页的实时速度、剩余时间、线程数与内置下载器**显示口径一致**；完成后 `dao.complete` 的 avgSpeed
按「引擎给出的总大小 ÷ 本段运行时长」算（`taskStartTimes` 在创建/继续时重置，与内置下载器同口径）。
暂停/失败/完成时都会把该任务从 `_stats` 移除，避免残留速度。

**设置页 UI**：下载引擎两项（`下载引擎选择` + `Gopeed 下载引擎` 入口）已挪到**「下载」分组最前**，
原来的「下载引擎」分组已删除。选 Gopeed 时用一段 `AnimatedVisibility(expandVertically/shrinkVertically)`
隐藏**确实只对内置分片下载器有意义**的 4 项：下载保存目录（引擎固定写 `Download/YunX`）、
最大同时下载任务数（引擎侧 `maxRunning` 写死 1）、下载速度限制（引擎不支持）、失败自动重试（引擎自己管）。
**保留**「下载线程数」（映射到 `opts.extra.connections`，真生效）、「免转存下载」（夸克取链方式，与下载器无关），
以及「锁屏后保持下载」「通知栏下载进度」——后两项对引擎任务**同样生效**（引擎任务走同一套前台服务、
WakeLock 与通知通道，见 §3.31），一开始误判成"只作用于内置下载器"藏起来了，已改回。
隐藏的 4 项都是分组中段的行，折叠后不影响首尾圆角（**以后改分组可见项时记得一起看圆角**）。

**已知边界（都是有意为之，不是 bug）**：引擎**不支持限速**，所以「速度限制」设置对引擎任务无效（已隐藏）；
内置下载器的分片/重试设置对引擎任务无意义（引擎有自己的连接与重试）。

**回退**：设置里切回「内置分片下载器」即可让新任务全部回到老下载器（引擎任务仍在，可手动删）；
要彻底拆掉就删 `engineTaskId` 列相关代码 + `startViaEngine`/`startEngineSync`/`pauseEngineTask`/`resumeEngineTask`
以及 enqueue/start/pause/remove 里的四个分支（DB 版本号别回退，理由同上）。

---

### 3.31 引擎任务的前台保活（复用内置下载器那一套，别另起一套）

**结论**：引擎跑在进程内（`GopeedEngine` 是 object 单例），**只要进程活着引擎就活着**，所以保活要做的只有一件事——
让「有引擎任务在跑」也算作「有下载在跑」，从而复用现有前台服务：

| 现有机制（都在 `DownloadManager`） | 引擎任务怎么接 |
|---|---|
| `onTaskStarted(id)`：第一个任务 → `DownloadService.start()` + `acquireWakeLockIfNeeded()` | 建任务成功、用户点继续时调用 |
| `onTaskFinished()`：最后一个任务 → `DownloadService.stop()` + `releaseWakeLock()` | 完成/失败/用户暂停/用户删除/引擎侧自己变 pause 时调用 |
| `notifyProgress(id, fileName, new, total)`：2 秒节流 + 从 `_stats` 取速度 | 同步循环里每轮回写进度后调用（**必须写在 `_stats.update` 之后**，否则通知里的速度慢一拍） |
| `DownloadService.notifyResult(...)`：终态通知（含流体云胶囊） | 完成/失败时调用，`promote = showSpeedProvider()` 与内置下载器一致 |

**★ 前台服务的起停一律走引用计数**：`DownloadService.acquire(context, title)` / `release(context)`
（§3.34 引入，`onTaskStarted/onTaskFinished` 内部已从 `start()/stop()` 换成这一对）。
同一时间只有一条前台通知，而保活来源不止 `DownloadManager` 一家（内核包下载也借它），
直接 `stop()` 会把别人的保活一起关掉。**新增调用方必须成对 acquire/release**。

**★ 配对靠 `engineKeepAliveIds`（`ConcurrentHashMap.newKeySet<Long>()`）**：`add(id)` 返回 true 才拉起保活，
`remove(id)` 返回 true 才收尾，所以无论从哪条路径终结都**恰好扣一次**（引擎任务不走下载协程，没有
`finally` 可以依赖，重复扣会让前台服务提前退出、漏扣会让服务一直挂着耗电）。
新增"任务终结路径"时**必须**补一个 `if (engineKeepAliveIds.remove(id)) onTaskFinished()`。

**进程重启后**：`markInterruptedAsPaused()` 会把引擎任务标成「已暂停」，保活集合是空的、不会误拉服务；
用户点继续时 `resumeEngineTask` 重新 `add` 并拉起。**进程被系统杀掉时引擎随之停止**，前台服务的作用是
大幅降低被杀概率，不是绝对保活（这也是没有把引擎做成独立进程的原因：独立进程要跨进程通信，成本远大于收益）。

---

### 3.32 下载引擎页（替换掉验证期的 Gopeed 测试页）+ 启动自动加载

**页面形态**：设置 → 「下载引擎」→ `app/src/main/kotlin/com/yunx/app/ui/screens/DownloadEngineScreen.kt`
（验证期的 `GopeedScreen.kt` **已删除**）。**一张卡片（圆角 20dp / 段内边距 16dp）里上下两段，
中间一条 `outlineVariant` 细线**：每段 = 圆角图标块（40dp / 12dp 圆角，选中段用 `primaryContainer` 高亮）
+ 标题（`titleMedium` + Medium）+ 可选小标签（「实验性」用 `Surface(primaryContainer)` 小圆角块，`labelSmall`）
+ 说明（`bodySmall` / `onSurfaceVariant`）+ **整宽按钮**（44dp 高 / 14dp 圆角 / `labelLarge`）：

**尺寸口径（用户反馈「字体布局那些有点太大了、不够现代美观」后整体压了一档）**：整页只允许标题 `titleMedium`、
说明与元信息 `bodySmall`、按钮文字 `labelLarge`、徽标 `labelSmall`——与设置页 `SettingsItem`
（`titleMedium` + `bodyMedium`）同一档；**别再往 `titleLarge` / 52dp 按钮 / 28dp 圆角 / 20dp 段内边距上加**。
间距：卡外 12dp、段内 12dp、元信息块（下载目录 / 权限）内 6dp。

| 段 | 主按钮 | 点击行为 |
|---|---|---|
| 内置分片下载器 | 当前引擎 → tonal 按钮「使用中」（带对勾、不可点）；否则描边按钮「切换到此引擎」 | 写 `downloadEngine = ENGINE_BUILTIN`（**不动**在跑的引擎任务） |
| Gopeed 引擎 | **没导入内核 → 描边按钮「导入内核」**（走 SAF 选 AAR）；已导入但没存储权限 →「先授予存储权限」（点它去授权）；已导入且未选中 →「切换到此引擎」；已选中 → tonal「使用中」 | 导入内核 / 授权 / 切换引擎 |

Gopeed 段里还带着：**引擎状态**（未导入内核 / 已导入，未启动 / 运行中）、内核大小与核心版本、下载目录、
存储权限状态与授权按钮，以及**只在已切到 Gopeed 时**出现的「重启引擎」和常驻的「删除内核」两个 `TextButton`。
**切换成 Gopeed 时若内核已导入且引擎没在跑，顺手把它启动起来**（`chooseEngine` 里做），省得用户再点一次。

**三条交互口径**（用户逐条反馈后定的，改之前先读这里）：

1. **没有存储权限就不给切到 Gopeed**（用户："要切换到 Gopeed 引擎时，必须要给存储权限，没给不给切换"）。
   判断用 `PermissionState`：`allFilesAccessRequired() && !allFilesAccessGranted()` 或
   `engineStoragePermissionPending(context)`，合成一个 `storageBlocked`（**提到 composable 顶部算**，
   因为 `chooseEngine` 的硬拦截也要用）。缺权限时主按钮文案/图标/行为都换成权限入口
   （「先授予存储权限」+ `FolderOpen` → `requestStoragePermission()`），**不是**切完再报错；
   `chooseEngine(ENGINE_GOPEED)` 里另有一道硬拦截：`notice = "还没有存储权限，不能切换到 Gopeed 引擎"` 后 `return`。
   理由：引擎写不了 SAF，没权限就只能落私有目录（11+ 用户在文件管理器里根本看不到），切过去等于白切。
2. **不提供「启动引擎 / 停止引擎」，只提供「重启引擎」**（用户："没切换到 Gopeed 却能点击启动引擎……
   我们不提供停止引擎的功能和启动引擎的功能，给他改成重启引擎"）。所以：
   - 引擎操作整行只在 `installed && engineOn` 时给（内置下载器模式下引擎不该在跑，删掉"启动"入口）；
   - 「重启引擎」= `GopeedEngine.stop()` + `start()`（`stop` 幂等、`start` 在 RUNNING 时直接返回端口，
     所以两者顺序不能反），重启后补一次 `engineVersion`；
   - **切回内置不停引擎**：切换只决定"新任务由谁执行"，页面顶部就写着"已存在的任务不受影响"，
     在跑的引擎任务被 stop 掉就自相矛盾了。引擎空转着直到进程结束或用户点「重启引擎」——可接受。
     **不要**在 `chooseEngine(ENGINE_BUILTIN)` 里加 `stop()`（我加过又删了）；
   - 「删除内核」不再要求用户先手动停止：它自己先 `stop()` 再 `uninstall()`（`uninstall` 在 RUNNING 时会抛
     「请先停止引擎」，旧 UI 有停止按钮时尚可自救，现在没按钮了就必须自己停）。
3. **内核状态要进页面先与真实文件对齐**（用户："选择内置下载器时（有导入内核），重启应用后引擎状态却显示未导入内核"）。
   根因：`GopeedEngine._state` 是进程内单例、初值 `NOT_INSTALLED`，只在导入/启动/停止/卸载时被写过；
   选内置下载器时 `YunXApp` 的启动流程**根本不会碰引擎**，于是状态永远停在"未导入"。
   修法：新增 `GopeedEngine.syncInstalledState(context)`（`RUNNING` 不动，其余按 `soFile().isFile` 写回
   `INSTALLED`/`NOT_INSTALLED`，只做一次 stat），在 `YunXApp.autoStartGopeedIfSelected` 开头
   （**在"选没选 Gopeed"的 return 之前**）和引擎页 `LaunchedEffect(Unit)` 里各调一次。
   `DownloadManager` 那两处 `state.value != RUNNING` 的判断只看"在不在跑"，不受这个同步影响。

**动效**（规格一律取自 `ui/theme/Motion.kt`，别另写时长；**透明度/颜色用 `effects*`、位移/尺寸用 `spatial*`**）：
**本页没有自己的入场动画**——它是由设置页「下载引擎」那一行做**容器变换**「长」出来的，
再叠一层淡入/上移会和形变打架（第一版写了 `fadeIn + slideInVertically`，已删）；
**引擎状态文字用 `AnimatedContent`** 淡入淡出（`effectsFast`）；图标块的底色/图标色走 `animateColorAsState`
（`effectsDefault`，与 `OnboardingScreen.kt` 里指示点颜色同一口径），切换引擎时「选中态」是渐变过去的而不是硬切；
内核就绪后才出现的下载目录/权限那一段用 `AnimatedVisibility` 淡入淡出。

**容器变换（Container Transform）接线**（和「主题与外观 / 关于云析 / 支持开发」三行完全同款，用户点名要这效果）：
源侧 = `MainScreen.kt` 在 `SharedTransitionLayout` 作用域里构造
`Modifier.sharedBounds(rememberSharedContentState(OVERLAY_KEY_GOPEED), animatedVisibilityScope = sourceScope)`，
作为 `engineRowModifier` 传给 `SettingsScreen`，再挂到那一行的 `SettingsItem(modifier = engineRowModifier)`；
目标侧 = `OverlayPage` 已按 `overlayKeyFor(...)` 用同一个 `OVERLAY_KEY_GOPEED` 做了 `sharedBounds`，**不用改**。
（`MainScreen` 里这几个源侧修饰符必须在 composable 作用域一次性建好再传下去：`rememberSharedContentState`
是 `@Composable`，不能包在普通 lambda 里延迟构造。）

**删内核必须顺手切回内置**（用户报的 bug：选了 Gopeed 再删内核，设置项还停在 Gopeed）：
页面里删完内核后若当前引擎是 Gopeed 就写回 `ENGINE_BUILTIN` 并提示「内核已删除，已自动切回内置分片下载器」；
`YunXApp.autoStartGopeedIfSelected` 里也做了同样的**自愈**（启动时若设置是 Gopeed 但内核不存在 → 写回内置）。
两道一起做的原因：`DownloadManager.shouldUseEngine()` 本来就会因内核缺失回退到内置下载器，
不修的话会出现「设置界面说在用引擎、实际跑的是内置下载器」的错位。
**删之前自己先 `stop()`**：`GopeedEngine.uninstall` 在 RUNNING 时抛「请先停止引擎」，旧 UI 有停止按钮时
用户可以自救，现在（口径 2）没有停止按钮了，必须由删除动作自己停。

**验证期那些测试功能全部去掉**：URL 输入建任务、当前任务进度/暂停/继续/删除、下载目录文件列表——
它们只是用来验证引擎能不能跑通，正式入口是解析页/网盘页/下载页的正常下载流程。

**设置页只留一行**：原来「下载引擎选择」+「Gopeed 下载引擎」两行合并成一行「下载引擎」（副标题显示当前引擎），
点击进独立页面；那个切换弹窗已删除。副标题靠 `ON_RESUME` 重新读 `settingsRepo.downloadEngine` 同步
（引擎在独立页面里改，返回设置页必须刷新）。`MainScreen` 那边沿用 `OVERLAY_KEY_GOPEED` / `showGopeed` 两个名字
（只是内部标识，指向的已经是新页面）。

**启动自动加载**：`YunXApp.autoStartGopeedIfSelected(ctx)`（在 `onCreate` 末尾调用）——
**第一件事是 `engine.syncInstalledState(ctx)`**（必须放在"设置里选的是不是 Gopeed"那个 `return` 之前，
否则选内置下载器时根本走不到，见口径 3）；然后设置选了 Gopeed **且**已导入内核时，起一个后台 `Thread`
把引擎加载起来（首次要 `System.load` 56 MB 的 .so 并初始化 Go runtime，提前加载能让第一个任务不必等）。
**任何失败只 `Log.e`**：引擎起不来不能影响应用启动，真正的下载会按失败任务落库并提示。
设置读取用 `SettingsRepository`（别自己拼 prefs 键名）。

---

### 3.33 下载目录统一口径 + Gopeed 自定义下载目录（SAF 反解 / 手输兜底）

**默认目录统一**（用户要求「默认的下载目录统一为 `/storage/emulated/0/Download/`」）：
新增 `app/src/main/kotlin/com/yunx/app/util/StorageDirs.kt` —— 两套下载器的**默认目录唯一来源**，
`defaultDownloadDir(): File` = `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)`，
`defaultDownloadPath(): String` 给 UI 展示。内置分片下载器本来就走 MediaStore 的 `RELATIVE_PATH = Download`
（`DownloadSaver.mediaStoreDestination`，无子目录时就是它），所以这里只是**把引擎的默认从 `Download/YunX`
改成 `Download` 根目录**：`GopeedEngine.PUBLIC_DIR_NAME` **已删除**，UI 里两处引用一并改掉（别再写死 "YunX"）。

**自定义目录为什么不能直接复用内置那套**：内置下载器存的是 `SettingsRepository.downloadDirUri`
（SAF `content://` tree Uri），而 Gopeed 是原生 Go 核心，`opts.path` **只认真实文件系统路径**，
给它 `content://` 直接失败。所以引擎另存一份 `SettingsRepository.engineDownloadDir`（`String`，空 = 默认目录）——
**同一个设置行，两种表示**：设置页「下载保存目录」这一行在两种引擎下都显示，只是存的东西不同。

**SAF → 真实路径（可行性结论：主存储可以，第三方 provider 不行）**：
`GopeedEngine.realPathFromTreeUri(uri: Uri): File?` —— 只认 authority `com.android.externalstorage.documents`
（系统「文件」应用里代表本机存储的那些位置），取 `DocumentsContract.getTreeDocumentId()` 得到
`primary:Download/YunX` 或 `1A2B-3C4D:xxx`，按 `:` 切开：`primary` → `Environment.getExternalStorageDirectory()`
（即 `/storage/emulated/0`），其余当卷名 → `/storage/<卷名>`，再拼上相对路径。
**第三方 provider（网盘 / Google Drive 之类）的 documentId 不对应任何真实路径，一律返回 null** ——
那里的调用方必须退到手输，**绝不能把 content:// 或它的 docId 当路径交给引擎**。
（`DownloadSaver.safDirDisplay` 早就在做类似的反解，但那条只取 `substringAfterLast(':')` 用于**显示**，
不能拿来当路径用。）

**落盘前的校验**：`GopeedEngine.prepareDownloadDir(path: String): File` —— 必须以 `/` 开头的绝对路径，
`mkdirs()` 建目录 + `canWrite()` 试写探针，失败抛 `IllegalArgumentException`，**消息直接给用户看**
（用 `require`/`throw` 的文案写清楚是"路径不对""建不出目录"还是"不可写"；不可写且缺「所有文件访问」时附带提示）。
设置页的 SAF 流程与手输弹窗都走它，口径一致。

**设置页交互**（`SettingsScreen` 的「下载保存目录」行，从 `AnimatedVisibility(!engineOn)` 里**移出来**常驻）：
- **行圆角固定在 `ListGroupPos.MIDDLE`，不要按引擎改成 LAST**。第一版按"引擎模式下它是可见分组的末行"
  改成了 LAST，被用户一眼看出不对：折叠掉的只是"只对内置有意义"的那几项，**组并没有结束**——
  这一行后面还有「免转存下载 / 锁屏后保持下载 / 通知栏下载进度」，整个「下载」组一直到最后的通知栏那行
  才收尾（那里才是 `LAST`）。改成 LAST 就成了"底边圆了却还接着下一行"。
- **组内 3dp 发丝缝（`ListGroupGap`）必须放在 `AnimatedVisibility` 外面**。第一版把补的 gap 放在了折叠块
  **开头**（为了内置模式行距不变），结果引擎模式一折叠间距就跟着消失 → 这一行与「免转存下载」贴在一起，
  也就是用户说的"边距错误"。正确做法：`Spacer(ListGroupGap)` 放在这一行**之后、AnimatedVisibility 之前**，
  块内只留原有的那几个 gap —— 两种模式行距都对，内置模式观感与改动前完全一致。
  （教训：`AnimatedVisibility` 折叠的是内部**全部**高度，包括里面的 Spacer；跨模式的间距不能藏在里面。）
- `onClick`：两种模式都先 `dirLauncher.launch(null)`（SAF）；
  **引擎模式**拿到 uri 后 `realPathFromTreeUri` → `prepareDownloadDir` → 写 `engineDownloadDir`；
  反解失败或不可写 → **弹手输弹窗**（`showEngineDirDialog`）；`ActivityNotFoundException`（选择器被卸载 #90）
  在引擎模式下也直接弹手输弹窗（内置模式维持原来的 Snackbar 提示）。
- `trailing`「恢复默认」：按当前引擎清对应的那一项（`engineDownloadDir = ""` / `downloadDirUri = null`）。
- 副标题：引擎模式空 = `Gopeed 默认 /storage/emulated/0/Download（点击自定义）`，非空 = `Gopeed：<路径>`。
- **不单独给「手动输入」按钮**（刻意的）：正常情况下 SAF 就能选到本机目录并反解出路径，
  手输只是兜底，硬塞进 trailing 会把这一行挤爆；触发路径就是"选了拿不到真实路径的位置"。
  以后若要暴露，priority 放在同一行的 trailing 里加第二个 `TextButton`，别做成独立入口。
- **`val engineOn` 必须声明在 `dirLauncher` 之前**（回调里要用它）；它原来声明在布局中间，已上移。

**运行期行为**：`resolveDownloadDir` 每 700 ms 的同步循环/建任务时都会调，自定义目录**不可写就记日志退回默认目录**
（SD 卡拔了、权限被撤销时，退回默认比让每个任务都失败好）；引擎页显示的「下载目录」就是这个函数的返回值，
所以自定义后打开引擎页能看到真实生效的目录。**改目录不影响已存在的任务**（和引擎切换同一口径）。

### 3.34 内核的云端获取（GitHub / 网盘自动下载 → 自动导入）

内核不再只能靠用户手动找 AAR：引擎页的「导入内核」按钮会弹一个来源菜单
**「从云端下载 / 导入本地 AAR」**，本地那条就是原来的 `OpenDocument` 流程，一行没动。

**整条链路都在 `data/gopeed/KernelProvisioner.kt` 一个对象里**（取包 → 下载 → 校验 → 导入 → 清理），
界面（`DownloadEngineScreen`）只收集它的 `phase: StateFlow<Phase>` 渲染弹窗，不持任何下载逻辑。
**刻意不复用 `DownloadManager.enqueue`**，三个原因：① 内核包落在**应用私有目录**
（`GopeedEngine.kernelTempDir` = `Android/data/<包名>/files/gopeed/kernel`），不放公共 Download，
所以也不需要任何存储权限；② 下载过程**不进「下载」页**、不落库、完成即自动导入；
③ 界面要的是「一个关不掉的进度弹窗」，不是任务列表里的一行。
复用的是底层零件：`ChunkDownloader`（Range 分片 + 断点续传 + 严格字节校验）、
`UpdateChecker`（Release 抓取，仓库参数化）、`QuarkResolveRepository`（网盘解析）、
`GopeedEngine.installFromAar`（导入）。

**已核实的硬事实（改之前先信这几条，别再猜）**：
- 内核仓库 `CYQawa/yunx_gopeed_build` 的 Release：tag `1`（name `Gopeed AAR`），
  资产是 **4 个按架构分包** `libgopeed-{arm64-v8a, armeabi-v7a, x86_64, x86}.aar` + `SHA256SUMS.txt`；
  body 里正好是 `[网盘下载](https://pan.quark.cn/s/fe29d0d39745)`，被现有的
  `UpdateChecker.netdiskDownloadUrl()` 直接命中（那个正则本来就是给更新弹窗写的，一份两用）。
- **网盘分享就是 GitHub 资产的同名镜像**：同样 4 个包名、大小逐一对应（合计 102,390,074 B）。
  所以网盘侧挑包的**唯一键是文件名**，不需要猜大小或顺序。
- **匿名（未登录）能下**：分享 token 不需要登录，游客取链返回 `dl-guest-*` 直链（要回带 `__pugs`），
  Range 可用（实测 `206` + `Content-Range`）。四个包 24.8–26.3 MB **全部低于夸克游客约 50MB 的上限**，
  所以「没登录也能自动下」是常态，「超上限」才是兜底提示。
- **ABI 只有一个对齐点**：`GopeedEngine.kernelAssetName()` = `libgopeed-${preferredAbi()}.aar`，
  下载挑包与 `installFromAar` 里「精确匹配 `jni/<abi>/libgojni.so`」必须同源；
  **Release / 分享里缺本机架构的包要直接报错，绝不静默换个架构下**（那样导入必然失败，还会被当成包坏了）。
  为此 `preferredAbi()` 从 private 改成 public。

**取链口径（用户指定）**：已登录夸克 → **转存优先**（`getShareDownloadLink`，会在用户网盘里建临时目录，
用完必须 `cleanupTempDir` 删掉），转存失败才回退**免转存**（`getShareDownloadLinkWithoutSave`）；
未登录 → 游客取链（`getGuestShareDownloadLink`）。直链请求头与「网盘下载」完全一致
（`Cookie` + `QuarkConstants.API_USER_AGENT` + `DownloadReferer`；游客态必须用 `link.guestCookie` 顶掉 Cookie，
缺了 CDN 412）。**转存临时目录的清理放在 `finally` 里且包了 `NonCancellable`** ——
协程被取消后普通挂起调用会立刻抛异常，不包就正好在「用户点取消」这条路径上泄漏用户网盘里的垃圾。

**下载**：分片数 = `min(目标片数, MAX_CHUNKS, 总大小/256KB)`。**网盘通道的目标片数是固定 64**
（`PAN_CHUNKS`，用户口径，**不看**设置页那个夸克档：夸克 CDN 按连接限速，连接越多越接近满速；
25MB 切 64 片每片约 389KB，仍远大于一个 TCP 窗口），GitHub 通道按设置页的「下载线程数」（GitHub 档）。
`MIN_CHUNK_BYTES` 因此定在 **256KB** —— 按 1MB 收敛的话 25MB 最多只能切出 25 片，根本到不了 64。
探不到大小就退回单流 `downloadFull`；服务器忽略 Range 时**清掉分片重来走单流**
（绝不按分片写整文件）。断点续传：分片文件留在私有目录里，下次下载按 `partFile.length()` 接着写。
镜像站下载会把直链作为 `fallbackUrl`，主地址失败自动回退（与「网盘更新」的 APK 同一口径）。
GitHub 通道额外做 **sha256 校验**（用 Release 资产自带的 `digest`，网盘通道拿不到摘要就跳过），
校验不过直接丢弃、绝不拿去导入。
**保活借的是内置下载器那条前台服务**（`DownloadService`）。★ 服务的起停**必须走引用计数版的
`DownloadService.acquire(context, title)` / `release(context)`**（本次为它新加的两个静态方法，
`DownloadManager.onTaskStarted/onTaskFinished` 也已从 `start/stop` 换成这两个）：
同一时间只有一条前台通知，而保活的来源不止一家（内置任务 + 内核包下载），
直接 `stop()` 会出现「内核下完把用户正在跑的下载的保活一起关掉」。
调用方自己配对：`DownloadManager` 用它的 `activeTaskCount`，`KernelProvisioner` 用自己的
`keepAliveAcquired` 标志 —— **一次都没 acquire 过就 release 会把计数打成负数，同样会误关别人的保活**。
内核侧的进度走通知栏（2 秒节流，与内置下载器同口径），收工（完成/失败/取消）在 `finally` 里 release。

**导入前后两件事**（都容易踩）：
1. 导入前**必须先 `GopeedEngine.stop()`**（`installFromAar` 不允许在 RUNNING 时覆盖 .so），
   导入后如果它原来就在跑，**立刻 `start()` 拉回来**，别因为更新内核把正在下载的任务晾着。
2. 新 `.so` **只有全新进程才 dlopen 得进来**（已加载的库卸载不掉），所以导入成功后页面提示
   「需要重启应用才会用上新内核」——这是 `installFromAar` 的既有语义，不是 bug。
   ★ 这条状态用 `GopeedEngine.pendingRestartForNewKernel`（本次新增的 `private set` 只读属性，
   由 `installFromAar` 按 `loaded` 置位）承载，**不要借 `lastError` 传**：内核更新流程是
   `stop()` → 导入 → `start()` 连着走的，而 `start()` 开头就会把 `lastError` 清空，提示会被顺手抹掉；
   何况 `lastError` 的 setter 是 `private`，外部连补写都做不到
   （第一版正是这么写的，CI 直接报 `Cannot access 'lastError': it is private in GopeedEngine`）。

**弹窗是故意关不掉的**：`AlertDialog(properties = DialogProperties(dismissOnBackPress = false,
dismissOnClickOutside = false))`，25MB 的下载不该被一次误触甩掉；要中断只能按「取消」
（`KernelProvisioner.cancel()` → 取消 OkHttp Call + 取消协程，分片保留可续传）。终态（完成/失败）才给「关闭」。
阶段文案跟着 `Phase` 走：获取版本 → 解析地址 → 分片下载（百分比 + 速度 + 分片数）→ 合并 → 导入。

**落点**：`data/gopeed/KernelProvisioner.kt`（新增，全链路）、`GopeedEngine.kt`（`preferredAbi()` 提 public、
新增 `kernelAssetName()` / `kernelTempDir()`）、`data/update/UpdateChecker.kt`
（`fetchLatestRelease(includePrerelease, repo)` + `Asset.size/digest`）、
`ui/screens/DownloadEngineScreen.kt`（菜单 + 底部弹窗 + 进度弹窗）。

---

### 3.35 应用内公告（远程列表 / 启动弹窗 / 未读角标）

后端是一个独立的公告服务（当前是 **PHP + SQLite 虚拟主机版**，域名 `http://yunx.cyqawa.os.kg`；
接口路径 / 参数 / 响应结构 / 错误码与原 Cloudflare Workers 版**完全一致**，只有域名与协议变了）。
客户端只用**两个公开接口**：`GET /api/v1/announcements?page=&pageSize=`（列表，**不含正文**）与
`GET /api/v1/announcements/{id}`（详情，含正文）。管理端 `/api/v1/admin/**` 需要 ADMIN_TOKEN，
客户端**一律不碰**。

★ **HTTP 明文与「全局禁明文」的冲突（换域名时必须一起动）**：服务端当前是 http，而
`res/xml/network_security_config.xml` 的 base-config 是 `cleartextTrafficPermitted="false"`
（全项目策略）—— 不放行的话 OkHttp 直接抛 `CLEARTEXT communication to … not permitted by network
security policy`，表现是公告**永远加载失败**（启动弹窗与未读角标都不出现，日志里那条 E 级就是它）。
所以那里为 `yunx.cyqawa.os.kg` 开了一条 `<domain-config cleartextTrafficPermitted="true">` 例外：
**后端上 HTTPS 后要同时删掉它、并把 `AnnouncementApi.BASE_URL` 改回 https**。
**绝不要**把 base-config 全局放开明文 —— 网盘 CDN / 更新下载 / 图床都会被降级到 http。
图床是**另一个域名**：http 图床同样要在那条 domain-config 里加一行（https 图床不用管），
没放行的 http 图床会加载失败（占位色 + 破图兜底，不崩）。

**三条接口口径（写错就是线上问题）**：
1. 成功与否看响应体的 `success` 字段，**不要只看 HTTP 状态码**（业务失败与 HTTP 错误码是分离的，
   非 2xx 也可能带合法 JSON 的失败原因，所以先读 body 再判 `success`）；
2. ★ **列表接口有副作用**：每次成功调用都会计入服务端当日「客户端启动数」⇒ **禁止轮询**。
   调用点只有两处：启动检查一次、用户在列表页手动刷新 / 翻页；
3. 详情接口会让 `viewCount` +1（预期行为）⇒ `AnnouncementViewModel` 里按 id **缓存详情**，
   同一会话同一条公告只请求一次（进详情、返回、再进都不会重复 +1）。
   ★ 但**刷新必须让缓存作废**，否则「服务端改了正文 → 刷新 → 再进详情」看到的还是旧内容
   （已踩过）：列表刷新 `refresh()` 先 `detailCache.clear()`；详情页右上角的刷新走
   `reloadCurrentDetail()` —— 刻意**不复用 `openDetail`**（那条路会把状态切成 `Loading`，页面闪一下加载态），
   保留旧内容、拿到新数据再整体替换，失败只弹 Snackbar 不把已有内容换成错误页。
   两处的代价都是 `viewCount` 再 +1，属用户主动刷新的预期行为。

**列表页大小固定 100（接口上限）**：启动检查就一次取满，于是「未读角标」与「启动弹窗候选」的口径
= **全部公告**而不是前 20 条；只有公告总数超过 100 条时列表页底部才会出现「加载更多」（同一 pageSize
翻第二页，分页口径一致）。翻页合并时按 id 去重（翻页期间若有新公告插入，服务端分页可能返回重复项）。

**已读口径完全由客户端维护**（服务端没有已读接口）：`AnnouncementReadStore` 用 SharedPreferences
存一个 JSON 数组（最新的在前，上限 500 条，超出从最旧的丢；不为它建 Room 表 —— 只是一堆 id，
没有查询需求）。**打开详情** 或 **关掉启动弹窗** 都算已读。角标数字 = 已加载到的公告里未读的条数。

**启动弹窗候选**（`AnnouncementViewModel.pickPopupCandidate`）：
1. 有未读的**置顶**公告 → 弹它（服务端已把置顶排在最前，取第一条未读置顶即可）；
2. 否则弹**最新的一条未读** —— ★ 不能直接拿列表首条：首条可能是「已读的置顶公告」，
   这时要按生效时间（`publishAt`，为 null 表示立即发布 ⇒ 退回 `createdAt`）取未读里的最大值；
3. 全部已读 → 不弹。
启动检查失败**静默**（与更新检查同一口径，只打 `YunX-Announce` 的 E 级日志），
用户点进公告页时会再拉一次，那时才把错误页显示出来。

**入口与共享元素**：顶栏右上角、收藏图标**左侧**（收藏只在解析页出现，公告是全局入口）。
未读 > 0 时叠一颗红点角标（>99 显示 `99+`）——★ 角标是 `Surface` + `Text` 手搓的，
**没有用 `BadgedBox`**：要自己控偏移与最小尺寸，也不想跟着 alpha 版组件 API 走；
公告页打开期间不显示角标（`overlayRoute == null` 才画）。
★★ **角标必须画在 `IconButton` 外面**（外层再套一个同为 48dp 的 `Box`）：
material3 的 `IconButton` 内部自带 `.clip(CircleShape)`（那颗 40dp 的圆形水波纹 StateLayer），
角标只要超出这颗圆就被切掉 —— 实测症状是「红点被切成水滴形」（已踩过）。
外层 Box 不裁剪、尺寸与 `IconButton` 一致，仍是同一个 48dp 点击区；角标自身没有 pointerInput，
点在角标上事件照旧落到 `IconButton`，不影响点击。
共享元素分三层，各管一段、互不干扰：
- 外层：顶栏公告图标 ↔ 公告整页，key = `OVERLAY_KEY_ANNOUNCEMENTS`（与收藏页同一手法）；
- 内层：列表项 ↔ 详情页，key = `announcementSharedKey(id)`，在 `AnnouncementScreen` 里**再套一层
  `SharedTransitionLayout`** —— 共享元素只在**同一个** layout 作用域内匹配，套一层就天然隔离了。
  ★ 容器变换必须 `resizeMode = SharedTransitionScope.ResizeMode.RemeasureToBounds`：
  列表项很窄、详情页是整屏，默认的 `ScaleToBounds` 会把详情页整体缩放（文字被拉伸）。
  「列表 ↔ 详情」仍用 MainScreen 那套**两个 AnimatedVisibility 互斥 + 「正在展示的 id」延迟清空**的写法
  （不用 AnimatedContent），但有两条硬要求，**别搞反**：
  ① 退出时长必须 ≥ 300ms，否则退出动画一结束内容就被移出组合、回收形变被截断；
  ② ★ `visible` 只能用**真实状态**（`detailId != null` / `detailId == null`）；那个「正在展示的 id」
     （`shownDetailId`）**永远不清空**，它的唯一用途是「退出期间渲染哪一页」。
     拿它当 `visible` 会让详情页**关不掉**（已踩过）：点返回 → 列表淡入、详情却仍「可见」
     ⇒ 动画一结束就闪回详情页；而此时 `detailId` 已经是 null，详情页那个返回按钮再点就是空操作
     ⇒ 观感是「返回按钮点不动了」。进入方向还要在点击处把两个状态**一起写死**，
     否则详情页被打开的首帧没有形变目标（只靠 `LaunchedEffect` 会晚一帧）。
- 内层再往下：详情页**图集缩略图 ↔ 全屏看图**，key = `announcementImageSharedKey(公告 id, 图集下标)`。
  源在详情页那个 AnimatedVisibility 里、目标在看图页那个 AnimatedVisibility 里，**同一个作用域**即可匹配
  （不必再套一层 layout）。可见性/退出同上面②：`viewerIndex` 管开关、`shownViewerIndex` 只管渲染哪一张，
  进入时两个状态一起写。第三层叠在详情页之上（同一个 `SharedTransitionLayout` 里**后写的 AnimatedVisibility**），
  黑底必须**不透明**：形变结束后缩略图那一份其实也被同步放大到整屏，不透明底把它盖住，否则透出重影。
  ★★ **源侧也必须跟着进/退场**（缩略图包一层 `AnimatedVisibility(visible = !正在看这一张)`），
    否则动画完全不生效：边界动画只认「**一个 outgoing + 一个 incoming**」——`BoundsAnimation.target`
    取的就是 `AnimatedVisibility.transition.targetState`，安静留在树里的缩略图 target 也是 true，
    于是状态机 `enabledEntries.fastFirstOrNull { it.target }` 会挑到**先注册的缩略图**当目标边界
    （而它的边界永远不变）⇒ 实测表现就是"过渡根本没生效"（已踩过）。
    让缩略图淡出后：点开时缩略图 outgoing（初始边界）、全屏图 incoming（目标边界）；关回来时角色互换，
    两个方向都对。缩略图的 AnimatedVisibility 放在**定尺寸外框里面**，布局尺寸因此不变、列表不抖。
  ★ 全屏看图**刻意不做左右滑动翻页**：翻页会让「当前这张」的 key 每滑一次就换一个，
  源/目标在下标之间反复重新匹配，最容易抖/串图；「一次看一张、关掉再点下一张」时源和目标全程是同一条。
  ★ 全屏看图的**缩放/平移只能加在共享元素内部**（`sharedBounds.fillMaxSize().graphicsLayer(...)`）：
    共享元素的形变本身就是靠 layer 位移 + 缩放实现的，加在外面会跟它打架。手势自研、不引第三方库：
    `detectTransformGestures`（双指缩放 + 单指拖动；以双指中心为锚点缩放
    `o' = (g-c) - (g-c-o) * (s'/s) + pan`，因为 `graphicsLayer` 是绕**中心**缩放的）与
    `detectTapGestures`（未放大时单击=关闭、放大时单击=复位、双击在 1x ↔ 2.5x 之间切换）两个
    `pointerInput` 并存；平移量按容器边界夹住，图不会被拖出黑边；换图（url 变）时缩放状态归零。

**图片：不加新依赖，复用 README 那一套**。`GitHubMarkdownImageTransformer` 里的加载逻辑已抽成
`RemoteImageLoader`（OkHttp + 内存 LRU 128 张 + `Semaphore(4)` + 总像素降采样 + svg 跳过 + GitHub 镜像），
Markdown 渲染器与普通图片（`RemoteImage`：公告封面 / 头像 / 正文图集）**共用同一份缓存**；
`GitHubMarkdownImageTransformer` 现在只是「渲染器适配层」（`mirrorPrefix` 转发给加载器）。
项目里**没有 Coil**，这是既定选择（见 `RemoteImageLoader` 的文件注释与本节），不要再引第二个图片库。
★ `RemoteImage` 的尺寸只有两种给法，别混：
1. **固定尺寸**（头像 / 列表缩略图 / **弹窗封面**）：调用方传 `Modifier.size(...)` 或 `height(...)`，
   宽高必须**都有界**（`Image` 在无界高度下会退化成图片固有尺寸，长图糊一屏）；
2. **只给宽度**：传 `Modifier.fillMaxWidth()` 且 `autoHeight = true`，内部用 `BoxWithConstraints`
   拿可用宽度、按位图比例算高度、再用调用方的 `heightIn(max = …)` 夹一次，最后 `Modifier.size(w, h)`。
★ **不要用 `fillMaxWidth` + `heightIn(max)` + `Modifier.aspectRatio` 这组写法**（已踩过）：
   公告弹窗封面就是它 —— 实测图片远超 180dp 上限、把标题和摘要压成一团，观感是「弹窗图片显示异常」。
   判断有界性也别拿 `maxWidth == Dp.Infinity` 比：无界时 `BoxWithConstraints` 给的是一个巨大的**有限** Dp，
   要用 `constraints.hasBoundedWidth/Height`。
★ 弹窗里的图**一律走固定高度**（公告弹窗封面 180dp + `Crop`）：弹窗高度必须可预期，
   按图片原始比例的话一张方图就能把弹窗撑满、把正文挤掉；想看全图点进详情页。
★ 详情页的图**全部收进正文下面的图集**（`announcementGallery` + `AnnouncementGalleryImage`），
   封面不再单独压在标题下：接口只给「封面 `coverImage`」+「正文图集 `images`」两块，没有统一图片数组
   ⇒ 「封面也算图集一张」这件事在前端拼：**封面排第 1 位并打「封面」标签**（`AnnouncementChip`，
   实心 primary 才在任何底图上都看得清），正文图按服务端顺序跟在后面，空串过滤、封面同时出现在
   `images` 里时**去重**（否则详情页出现两张一样的图）。
   下标口径只有 `announcementGallery` 一个来源：全屏看图的下标和共享元素 key 的下标都用它的下标，别再各算一套。
   ★ 排布分两种（`hero` 参数，共享元素与 key 是同一套）：**只有一张**时铺满宽度、按原图比例完整显示
   （"有封面、没正文图"是最常见的公告，缩成小方块等于白丢信息）；**多张**才横向 `LazyRow`
   （132dp 正方形 `Crop`，原图比例差异大，`Fit` 会让行高参差不齐）。
★ 参与共享元素的图片**尺寸必须由约束决定，不能写死 `size`**：`RemeasureToBounds` 形变时会用
   **动画中的尺寸**去重新测量内容（这是它和 `ScaleToBounds` 的区别），写死尺寸的话内容不会跟着放大。
   正确写法是两层：外层给尺寸（多图用 `Modifier.size(132.dp)`、单图用 `fillMaxWidth`）把**布局**尺寸钉住
   （横向列表不会因为某一项"变大"而抖动），内层 `fillMaxSize()` / `fillMaxWidth()` + `then(sharedBounds…)`
   当共享元素本体；全屏看图侧则直接 `sharedBounds.fillMaxSize()`（配 `Fit`，内容每帧正好等于形变框）。
★ 全屏看图是**深色底**：`RemoteImage` 的占位底色要传 `placeholderColor = Color.Transparent`，
   默认的 `surfaceVariant` 会在纯黑上显示成一块灰板（`contentScale = Fit` 的留白处也应当是黑底）。
★★ **`produceState(initialValue, key)` 的 `remember` 不带 key ⇒ 绝不要用它加载「会变的 URL」**（已踩过）：
   它的实现就是 `remember { mutableStateOf(initialValue) }`，key 变了状态里仍是**上一张图**；
   加载逻辑若再写成「已有值就跳过加载」，新图就永远不会请求 ⇒ 打开公告 A 再打开公告 B，
   B 的封面 / 头像 / 正文图全是 A 的（**文字是直接传参所以正常**，很容易误判成"数据串了"）。
   必现路径：详情命中 `detailCache` 时不经过 Loading 分支，详情页整棵 `LazyColumn` 原地换内容。
   正确写法（`RemoteImage` 与 `GitHubMarkdownImageTransformer` 都已这么改）：
   `remember(link) { mutableStateOf<Bitmap?>(cached(link)) }` + `LaunchedEffect(link) { … }` ——
   URL 一变状态就同步重建：旧图立刻消失、缓存命中直接出图、失败只留占位色。
   同理，详情页的 `LazyListState` 要按公告 id `scrollToItem(0)`，否则复用槽位时会继承上一条的滚动位置。

**正文渲染不用 WebView**：走 mikepenz GFM 渲染器（与 README 预览同一套 + `compactMarkdownTypography()`），
`content` 里的 HTML 片段只会当普通文本显示。这样**没有脚本执行面**（XSS / CSP 都不用自己扛），
代价是 HTML 不解析 —— 这是刻意的取舍，别为了「支持 HTML」换成 WebView。
图片的明文口径见本节开头：API 域名已在 network_security_config 放行，图床域名要单独放行（或用 https）。

**时间**：服务端统一 UTC ISO 8601。★ **不用 `java.time`**（minSdk 24 没有，也没开 core library
desugaring），统一走 `AnnouncementTime.kt` 的 `SimpleDateFormat`；`'Z'` 是字面量、必须配
`timeZone = UTC` 解析，不带引号的 `Z` 才是 RFC822 时区。

**落点**：`data/announcement/{AnnouncementApi,AnnouncementReadStore,AnnouncementTime}.kt`、
`ui/viewmodel/AnnouncementViewModel.kt`、`ui/screens/Announcement{Screen,ListScreen,DetailScreen}.kt`
（宿主 + 列表页 + 详情页；宿主里还有**全屏看图页** `AnnouncementImageViewerPage` 这条共享元素链路，
详情页里有 `announcementGallery` / `AnnouncementGalleryThumbnail` 这套图集口径）、
`ui/components/{RemoteImageLoader,RemoteImage}.kt`、
`ui/MainScreen.kt`（图标 + 角标 + 路由 + 启动弹窗）。

---

### 3.36 凭证密钥失效（改锁屏密码必读）

**症状（1.2.8 线上三份崩溃报告，同一根因三种形态）**：用户在**设置里改了锁屏密码/指纹**、
或系统/厂商 keystore 升级后，App 冷启动即崩，栈顶落在
`AndroidKeystoreCredentialCipher` 的 `cipher.init` 上：

```
java.security.InvalidKeyException: Keystore operation failed
Caused by: android.security.KeyStoreException: Key not found
Caused by: android.security.KeyStoreException: Invalid key blob（internal code -33，
            upgrade_keyblob_if_required_with）
android.security.keystore.KeyPermanentlyInvalidatedException: Key permanently invalidated
```

**机制**：密钥建在 `AndroidKeyStore`（别名 `yunx.account.credentials.v1`，AES-GCM，**没有**也不该有
`setUserAuthenticationRequired(true)`）。但 keyblob 在部分 ROM/系统版本上会随设备凭证变化而
**永久解不开**——查无此键、blob 无法升级、或被系统直接作废。三种报错都是同一件事：
**只有这一把密钥能解的密文，全废了**（六个平台账号 + GitHub Token + 下载任务请求头）。

**修复前为什么是「崩」而不是「重新登录」**：两处叠加。

1. `SecureAccountDaos` 里每处都是 `stored?.let { decryptXxx(...) }`，而 `decryptXxx` 是 **suspend
   函数** —— **接收者表达式先于 `withContext` 求值**，所以 `key()` 抛的 Keystore 异常
   **根本没进** `withContext` 里的 `try`，直接从 `getAccount()` 冒到调用方协程（主线程）。
2. 例外异常类型是 `Error` 系（如 `ProviderException`）或 `KeyStoreException` 时，
   `catch (error: Exception)` 的旧写法也接不住。

**现在的口径（改这块之前先读）**：

- `CredentialKeyException` 分**两种**，上层必须区分（`CredentialStore.isKeyLost`）：
  - `PermanentlyInvalid`：条目永久失效 ⇒ 密文再也解不开 ⇒ 该清就清 + 提示重登；
  - `Unavailable`：Keystore **暂时**进不去（设备还锁着等）⇒ **只返回 null，绝不删数据**，
    删了等于把用户本来还能解开的账号白白作废。
- `AndroidKeystoreCredentialCipher` 自愈：**条目坏了**才删坏条目 → `generateKey()` 建新密钥 →
  **整段加解密流程重试一次**。重试必须在 `withRetry` 那一层包住整段，不能只重试 `key()`——
  部分机型把「初始化失败」推迟到 `doFinal` 才报。
- **删键前必须先确认 Keystore 可达**（`discardStaleEntry` 里先 `openKeyStore()`）：
  连 `KeyStore.load` 都进不去时抛 `Unavailable`，不许删。
- `isKeyProblem` 是**严格白名单**，而且**判断顺序有陷阱**：`AEADBadTagException` 是
  `GeneralSecurityException` 的子类，**必须先单独排除**，否则「密文被改 / 跨版本残留」会被
  误判成密钥故障 ⇒ 把**好密钥**删掉 ⇒ 全部账号真的作废。
- `AndroidKeystoreCredentialCipher.shared` 是**全进程唯一实例**：DAO（`AppDatabase.get`）、
  `GitHubTokenStore`、`DownloadManager` 三处必须共用。分开 new 会让 `cachedKey` 各缓存一份、
  `onKeyProvisioned` 只被最后一个注册者收到。
- `onKeyProvisioned` 只在**真的 `generateKey()`** 时回调（不是「失败过」）：首次启动本来就没键，
  不能据此判定「旧密文作废」。
- 提示链路：数据层只写一个 SharedPreferences 标记（`CredentialStore.installRecovery` 在
  `YunXApp.onCreate` 装配，必须早于任何凭证读写），`MainScreen` 弹一次 `AlertDialog`。
  标记用 `commit()` 而不是 `apply()` —— 同一线程内先写后读，异步落盘的旧值会让提示不弹。

**自愈路径（各自清各自那条，不做全局清库）**：账号走 `SecureAccountDaos.decryptGuarded`（并给
`observeAccount()` 挂 `.catch { emit(null) }`，异常击穿的是收集方协程）；GitHub Token 走
`GitHubTokenStore.getToken` 的失败删除；下载请求头走 `DownloadManager.loadPersistedHeaders` 的
`runCatching`（重置成空表）。**故意不在失钥回调里无差别清空账号表**——判断稍有偏差就不可恢复，
宁可让每个平台各自失败一次。

**落点**：`data/security/CredentialCipher.kt`、`data/db/SecureAccountDaos.kt`、
`data/db/AppDatabase.kt`、`data/network/GitHubTokenStore.kt`、`data/download/DownloadManager.kt`、
`ui/MainScreen.kt`、`YunXApp.kt`；回归测试 `app/src/test/kotlin/com/yunx/app/data/db/SecureAccountDaosTest.kt`
（CI 跑 `./gradlew testDebugUnitTest`，三个用例钉住「不崩 / 永久失效清数据 / 暂时不可用保数据」）。

---

## 4. 验证


写完代码后逐项自查，然后交付：

0. **先跑两个脚本**（本地没有编译器，这两个能挡住大部分 CI 往返）：
   - `python3 ~/.yunx-bal/ktcheck.py <改动的 .kt ...>`：词法检查（注释嵌套 / 字符串闭合 / 括号配对）；
   - `python3 ~/.yunx-bal/impcheck.py`：**必须整项目跑**（无参数），找「用了但没 import」的符号 ——
     它靠"别处 import 过这个名字"当证据，只扫单文件会漏报。已用它验证过：故意删掉
     `pointerInput` 的 import，它会直接报出与 CI 完全相同的行号。
1. **import 是否齐全**：新用到的 Composable、动画 API、图标、协程 API 都有对应 import。
2. **实验性 API 注解**：见下方「常见编译坑」表。
3. **符号一致性**：改了函数签名后，`grep` 一遍旧签名/旧调用点，确认无残留。
4. **Room 一致性**：改了表结构则 `version` 已 +1、Migration 已写且已注册。
5. **命名与风格**：与同目录同类文件一致。



### 常见编译坑

| 坑 | 处理 |
|---|---|
| `FlowRow` / `FilterChip` | 需 `@OptIn(ExperimentalLayoutApi::class)` / `ExperimentalMaterial3Api` |
| `combinedClickable` | 需 `@OptIn(ExperimentalFoundationApi::class)` |
| `Unresolved reference 'pointerInput'` + 同 lambda 里 `detectTransformGestures` / `size` / `detectTapGestures` 全报 `Cannot infer type for this parameter` | **一个 import 缺失、级联一片**，别一个个改它们。`pointerInput` 在 `androidx.compose.ui.input.pointer`，而手势检测器（`detectTapGestures` / `detectTransformGestures` / `detectDragGestures`）在 `androidx.compose.foundation.gestures` —— 两个包容易记混。pointerInput 解析不出来 ⇒ lambda 收不到 `PointerInputScope` 接收者 ⇒ 里面的成员/扩展全跟着报错。已踩过一次（全屏看图的双指缩放）。用 `impcheck.py` 先定位真正缺的那一个 |
| 图标找不到 | 已引入 `material-icons-extended`，确认图标名与 `Outlined`/`Filled` 命名空间 |
| `rememberSaveable` 报 `Unresolved reference` | 包名是 `androidx.compose.runtime.saveable.rememberSaveable`（**不是** `runtime.rememberSaveable`）；写错会级联出一片 `Unresolved reference 'it'` / `@Composable invocations can only happen…`，别被后面的报错带偏 |
| `animateColorAsState` 报 `Unresolved reference` | 包是 `androidx.compose.animation.animateColorAsState`（**不是** `androidx.compose.animation.core`）。判断依据：`.animation` 放的是**进出场/内容切换**（`AnimatedVisibility`/`AnimatedContent`/`fadeIn`/`fadeOut`/`slideInVertically`/`togetherWith`/`animateColorAsState`），`.animation.core` 放的是**时间曲线与动画值**（`tween`/`spring`/`Animatable`/`animateFloatAsState`/`animateDpAsState`）。写错包会连带一片 `Cannot infer type for this parameter`（`by` 委托推不出类型） |
| Room 编译报 schema 错 | 检查 `version` 是否 +1、Migration 是否注册 |
| `Unclosed comment` + 一串「莫名其妙」的语法错（如 `Identifier expected`，行号还指着一段正常代码） | **注释正文里出现了 `/*`** —— Kotlin 的块注释**支持嵌套**，多出来的 `/*` 会一路吞到文件尾，报错行号与真正的位置无关（别顺着行号改）。典型来源：注释里写接口路径 `/api/v1/admin/**`、通配路径、正则片段。已踩过一次：公告客户端 KDoc 里的 `/api/v1/admin/**` 让整个文件被注释掉 ⇒ 搜本次改动文件注释内的 `/*` |

---

## 5. 下载引擎重点笔记（改动前必读）

`DownloadManager.kt` 是全项目最容易改错的文件，以下机制都是为修复真实线上问题而存在的，**不要随意"简化"**。

### 5.1 任务池 + 弹性区模型

```
分片规划：chunkCount = chunkCountFor(total, threads)
          主池 = chunkCount × 0.7   → 文件 part_0 … part_{n-1}（等分区间）
          弹性区 = 剩余 30% 字节     → 文件 seg_{start}_{end}.part（按序领 4MB 块）
并发 worker = actualWorkers = min(effectiveWorkers, MAX_INFLIGHT_CHUNKS)
在飞上限 = inflightLimiter（★ 全进程共享的 Semaphore，跨任务生效，绝不手动 release）
```

- worker **循环领片**，慢片不阻塞其他线程 → 根治"尾部并发塌缩"。
- 弹性区用 `ElasticAllocator` **按字节顺序**分配，替代早期的"中点劈分"（劈分会导致主池耗尽瞬间全部线程涌入、区间跨度翻倍、连接复用率崩塌 → 中后段掉速）。

### 5.1.1 全进程在飞上限（**不要改回「每任务一个信号量」**）

```kotlin
在飞上限 = inflightLimiter（★ 全进程共享的 Semaphore，跨任务生效，绝不手动 release）
容量 = MAX_INFLIGHT_CHUNKS = inflightChunksFor(Runtime.getRuntime().maxMemory())
     = clamp(maxHeap / 8 / BUFFER_SIZE, 8, 512)      // BUFFER_SIZE = 64KB
并发 worker = actualWorkers = min(effectiveWorkers, MAX_INFLIGHT_CHUNKS)
分片 IO 线程池 = chunkIoDispatcher（★ 专用线程池，不能退回去用 Dispatchers.IO：它把并行度钉在 max(64, 核数)）
```

- 旧实现是**每任务一个** `Semaphore(effectiveWorkers)` —— 容量恰等于自己创建的 worker 数，永不阻塞，等于从不限流。
- **2026-10-01 修正（推翻了「512 线程 × 256KB = 128MB」这个简化归因）**：OOM 的直接机制在 OkHttp 侧 ——
  客户端声明 `OKHTTP_CLIENT_WINDOW_SIZE = 16MB`（`Http2Connection.kt:114/993`，`Http2Stream.maxByteCount`
  就取这个值），消费端一慢（写盘慢、限速 `speedLimiter.awaitAllow` 挂起、落盘节流），读线程仍会填满该流
  readBuffer 的 16MB；几十路 × 16MB 远超 256MB 堆 —— 两份 OOM 报告的栈（`SegmentPool.take` ←
  `Http2Stream$FramingSource.receive`）正是这里。该控的是**并发流数**与**每路读缓冲**，不是线程数
  （`log/3/2/` 的两份日志里 60 路连接跑出 3~7MB/s、单连接 50~123KB/s，说明几十路已能打满链路）；
  同日下载客户端固定 HTTP/1.1（见下一条）后，这条 16MB 的每流窗口已不复存在。
- **并发真正由 `chunkIoDispatcher` 决定（2026-10-01 起）**：分片 IO **不能**再跑 `Dispatchers.IO` ——
  它的并行度被钉在 `max(64, 核数)`，超出的 worker 只在队列里干等，于是「设置里 512 线程」永远只跑得出 64 路。
  现在主池、失败重试的 worker 与 `ChunkDownloader` 内部的 5 处 `withContext` 都走
  `DownloadManager.chunkIoDispatcher`（`ThreadPoolExecutor(core = max = MAX_INFLIGHT_CHUNKS, 30s, LinkedBlockingQueue)`
  ＋ `allowCoreThreadTimeOut(true)`，线程名 `yunx-chunk-io`、daemon ⇒ 按需创建、空闲回收）。
  ★ 必须 core = max：`core = 0` + 无界队列在 ThreadPoolExecutor 里只会养出 1 个 worker（等于退回单流）。
- **2026-10-01 下载客户端固定 HTTP/1.1**：`HttpClients.buildDownload()` 的
  `.protocols(...)` 由 `listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)` 改为 `listOf(Protocol.HTTP_1_1)`，
  从源头去掉上一条的「每流 16MB 应用层接收窗口」——HTTP/1.1 没有流窗口，读多少完全由 TCP 背压决定，
  堆占用只剩每路 64KB 读缓冲（代价：分片不再多路复用，每路各占一条连接）。是否影响总速**必须实测**：
  验证办法：需要在 `ChunkDownloader` 里临时打一行 `response.protocol`（或抓包）确认协商到 `http/1.1`，
  再对比 `runTask:` 的总速；要回退只改那一行为两个协议即可。API 客户端（`buildApi()`）**不设** `protocols`，
  仍按 OkHttp 默认（h2 优先）——JSON 响应体小，没有流缓冲问题。
- 主池、弹性区、**失败重试**三条路径都必须走 `inflightLimiter.withPermit` —— 少任何一条，三路并发就会叠加。
- `threadCount` 仍**原样传给 `chunkCountFor`**：`plan.txt` 签名（`chunks=… total=… main=…`）不能变，否则所有用户的断点续传失效。钳的是 worker 数，不是分片数。
- 单路读缓冲 `ChunkDownloader.BUFFER_SIZE` = 64KB（原 256KB）；`HttpClients.buildDownload()` 排队上限 `MAX_QUEUED_CALLS = 64`、空闲连接池 8 条 / 1 分钟（原 512 / 64 条 × 5 分钟），并在 `Application.onTrimMemory` 调 `HttpClients.evictIdleConnections()`。
- 边界由 `InflightChunkBudgetTest` 守住：小堆保底 8 路、大堆封顶 512 路、总缓冲不超过最大堆的 1/8。
- 设置页档位已恢复到 512（`SettingsRepository.MAX_DOWNLOAD_THREADS = 512`，`threadOptions` 到 512），**上限 512 与档位一致 ⇒ 选 512 就是真的 512 路**：256MB 堆按预算算得 512（`512 × 64KB = 32MB = 堆的 1/8`），FD 实测软限 32768（`/proc/self/limits`，512 路 ≈ 1100 个连接+句柄）。真实值仍见日志 `runTask:` 行的 `actualWorkers`（低内存机型会被堆预算夹到 512 以下）。因为 `chunkCountFor` 在 `threads ≥ 64` 时结果恒等（`want = threads × 8` 已 ≥ 512 硬封顶），`plan.txt` 签名不变，断点续传不受影响。同理，选 512 也可能只是多撞 CDN 的同 IP 限连（`尝试N IO异常`），不一定更快。

### 5.2 `chunkCountFor` 的真实语义（易被误读）

```kotlin
val minChunkBytes = 256 * 1024L            // 「单片最小 256KB」= 分片数上限阀，不是"每片就是 256KB"
val bySize = when {                        // 按文件大小的基础分片数
    total < 5MB -> 1;  total < 50MB -> 8;  total < 500MB -> 32;  else -> 64
}
val want = maxOf(bySize, threads * 8)      // 每线程平均 8 片盈余
return minOf(want, (total / minChunkBytes).toInt(), 512)   // 512 为硬封顶
```

**实际单片大小 = `ceil(total / chunkCount)`**，并非固定 256KB——大文件的单片远大于 256KB，线程数越高、分片数封顶后单片越大。主池片就是这个大小（`DownloadManager.kt:926` 的 `part_$i`），只有弹性区（后 30%）才用 `ElasticAllocator` 按实测速度动态定块（`clamp(单路速度 × 5s, 256KB, 4MB)`，尾部收缩到 `remaining / workers`、下限 64KB）。

同时注意：分片数还会被 `total / minChunkBytes` 夹住，所以**小文件的分片数（进而实际并发路数）可能低于用户设置的线程数**，这是当前设计为避免碎片化而做的取舍。排查"线程数设置没生效"类问题时先核对这一层。

### 5.3 CDN 并发限制（硬编码上限的由来）

```kotlin
private const val RANGE_WORKERS_CAP = 8          // 迅雷等 CDN 单文件并发 Range 阈值
private const val RANGE_IGNORED_TOLERANCE = 3    // 偶发 200 容忍次数，超过才回退单流
private const val STAGGER_CAP = 8; STAGGER_MS = 25L  // 错峰建连，平摊 TCP/TLS 突发
```

- 迅雷并发超过约 8 会被降级为 `200` 整文件响应（忽略 Range）→ 整任务回退单流、速度暴跌。
  故 `SettingsRepository.XUNLEI_DOWNLOAD_THREADS = 8` **固定不可改**，`setDownloadThreads` 对迅雷直接 return。
- 提高任何平台的并发上限前，**必须实测是否触发 200 降级**，"并发越大越快"在网盘 CDN 上不成立。

### 5.3.1 慢连接抢占（治「收尾塌到 KB 级」，**不要删**）

网盘 CDN 是**按连接**限速的，且个别连接会落在慢节点上。真机日志（70.6MB 文件，当时 `YunX-DL` 的诊断输出，日志已删）实测：

```
runTask 诊断: id=16 在飞=1 used=1/64 剩主池片=0 总速=3.2 KB/s 已下=70.6MB/70.6MB 剩余=25.0KB
  └ m169 起点=44094960 块大小=256.3KB 已收=231.3KB 瞬时=3.2 KB/s 均速=5.4 KB/s 已跑=42s
分片结束: id=16 m169 收=256.3KB/256.3KB 耗时=49s 均速=5.2 KB/s
```

同一时刻其余 61 路早已空转 —— **最后 500KB 拖了 37 秒**。多数连接 40~80KB/s，慢的只有 3~7KB/s，
说明不是「整站变慢」，而是那几条连接坏了；而慢分片原先会一直跑到死，没有任何换连接的机制。

机制（`DownloadManager` 看门狗 + `ChunkDownloader`）：

```kotlin
private const val PREEMPT_MIN_BPS = 12 * 1024L      // 绝对下限：低于 12KB/s 才算慢（正常 40~80KB/s）
private const val PREEMPT_MIN_AGE_MS = 15_000L      // 至少跑 15s 才判（避开建连/TCP 爬坡）
private const val PREEMPT_COOLDOWN_MS = 10_000L     // 同一分片两次抢占之间的冷却
private const val PREEMPT_MIN_REMAIN = 128 * 1024L  // 剩余太少就不折腾
private const val PREEMPT_MAX = 3                   // 单分片最多抢 3 次（全站慢时防重连风暴）
private const val PREEMPT_PER_TICK = 2              // 每轮（5s）最多抢 2 路
private const val PREEMPT_ENDGAME_INFLIGHT = 3      // 在飞 ≤3 视为收尾：放宽年龄/剩余门槛
private const val PREEMPT_ENDGAME_MIN_AGE_MS = 3_000L
判定阈值 = max(PREEMPT_MIN_BPS, 本任务平均单连接速度 / 2)
```

- 看门狗 = 每 `PREEMPT_TICK_MS`（5s）的采样协程：`sampleInflightChunks` 刷新每路瞬时速度后交给
  `preemptSlowChunks` 判定；命中只把 `InflightChunk.preempt` 置位。
- **收尾放宽**：在飞 ≤ `PREEMPT_ENDGAME_INFLIGHT` 时不再要求「跑满 15s / 剩余 ≥128KB」——只剩几路在磨时，
  那几路的速度就是用户看到的总速度，重连握手（~0.5s）比继续等便宜得多。
- `ChunkDownloader.downloadChunk(preempt = …)` 读到置位即 `throw PreemptedException`：**已写字节全部保留**，
  下一轮从 `partFile.length()` 续传，**不退避、不计失败**（`ChunkResult` 对外仍是三态）。
- 因此抢占**永远不丢数据、不产生空洞**：`written == expected` 校验与合并前的字节校验照旧。
- 抢占计数/`抢占慢连接 …` 日志、`sampleInflightChunks` 采样与 `InflightChunk.preempt / preemptCount /
  lastPreemptAtMs` 都是**永久逻辑**（抢占判定依据）。排查用的临时诊断日志（在飞快照 `runTask 诊断`、
  `分片结束`、`弹性块分配`/`弹性块结束`、`单流诊断`、`协议诊断`）已于 2026-10-01 删除；
  **不要因为「日志都删干净了」就把这些字段和采样一起删掉**，否则收尾长尾会回来（见 `PREEMPT_MIN_BPS` 注释）。
- 2026-10-01 的另两份复现日志（`log/3/2/`）显示同一形态的变体：总速 3~7MB/s 全程正常，
  但主池慢片 `m34 21s/12.0KB/s`、`m93 18s/13.6KB/s`、`m37 17s/14.6KB/s`、`m128 16s/15.7KB/s`
  在别的片以 4s/50~60KB/s 完成时还在爬，收尾最后 200~400KB 只剩 1~5 路（`seg@58726398 233.3KB
  已收=111.3KB 瞬时=18.3KB/s`）→ 正是抢占要处理的对象。

### 5.4 断点续传与分片计划签名

`plan.txt` 内容形如 `chunks=37 total=39536652 main=25`。
跨会话改线程数或服务器探测大小变化会使旧 `part_i` 区间错位 → 检测到签名不一致时**整目录清空重下**。改动分片规划算法会让所有用户的现存断点失效，需在 PR 里说明。

分片缓存目录：`context.externalCacheDir/download_tmp/{taskId}/`
（即 `/storage/emulated/0/Android/data/com.yunx.app/cache/download_tmp/{id}`）

### 5.5 进度落盘必须节流（ANR 历史）

`dao.updateProgress` 写库会触发全表 Flow 重发 → 主线程全列表重组。早期按字节（256KB）节流导致高速下载每秒写库几十次 → **ANR**。
现为 **按时间节流 500ms**（`progressPersistIntervalMs`），UI 进度走内存 `_stats`（`StateFlow<Map<Long, DownloadStats>>`）高频展示，DB 低频持久化。
**不要把落盘改回按字节触发。**

### 5.6 并发安全要点

- `activeJobs` 的注册/移除全程在 `jobsLock` 内，防 start/pause/remove 的 TOCTOU 竞态。
- `finally` 中只移除**自己注册的** deferred（`if (activeJobs[id] === deferred)`），否则"暂停后立即恢复"会误删新任务注册。
- `taskLocks` **不在 finally 清理**，否则会误删新任务的锁导致并发写分片。
- 暂停时以**磁盘 part/seg 真实长度**回写进度，避免恢复时进度回跳。
- 进度累加一律 `minOf(..., total)` 钳制，防显示"已下载 > 总大小"。

### 5.7 合并阶段进度（别让界面停在 100%）

下载完成后 `finishDownload` 要把所有 `part_i` 顺序写进最终文件；几 GB 的文件这一步要几十秒，
早期这段时间界面完全不动、通知还挂着最后的下载速度 → 用户以为卡死。现在：

- `ChunkDownloader.mergeChunksToStream(chunkFiles, out, onProgress)` 每写完一个分片回调一次「已合并字节数」。
- `DownloadManager.finishDownload` 用 `mergeReportIntervalMs = 300L` 节流（百分比没变时）上报：
  - `_stats.update { it + (id to DownloadStats(mergePercent = percent)) }`；
  - `DownloadService.update(context, fileName, percent, DownloadService.MERGE_TEXT, showSpeedProvider())`，
    通知正文因此显示「正在合并分片，完成前请勿关闭应用」（`MERGE_TEXT` 让 `buildNotification` 走合并分支，别把它当速度拼成"下载速度 合并中"）。
- UI 侧唯一判据是 `DownloadStats.mergePercent`（默认 `-1` = 不在合并）：`DownloadScreen` 主任务行 / 子任务行
  的进度条与文案切成「合并中 · n%」，文件夹组徽标显示「合并中」。

**为什么不在 DB 里加 `STATUS_MERGING`**：合并是进程内的短暂阶段，进程被杀合并本来就中断（分片还在，恢复即可），
库里多一个状态只会换来"重启后永远卡在合并中"这种脏数据；同理也不要让 UI 用 `downloadedSize == totalSize` 判断合并（暂停/失败时同样成立）。
合并期间「暂停」按钮照旧有效：取消协程 → `dest.abort()` 删半成品 → 按磁盘分片长度回写进度变「已暂停」。

---

## 6. 代理工作规范

### 6.1 动手前

1. **先读同类文件**再写新代码（如加页面先读 `BookmarkScreen.kt` / `AboutScreen.kt`）。
2. 涉及下载引擎、Room 迁移、凭证加密的改动，**先说明方案并等用户确认**再改。
3. 不引入新依赖、新架构（DI 框架、Navigation-Compose、其他网络库）——除非用户明确要求。若必须引入新依赖，请向用户告知，说明必要性，并取得同意

### 6.2 改动中

- 注释用中文，解释**为什么**这样写（尤其是绕过某个平台限制的 workaround），项目现有注释即为范例。
- 保持"数据层 → ViewModel → UI → 入口接线"的顺序推进，改完做一次符号一致性检查（`grep` 旧签名残留）。
- 不做超出任务范围的顺手重构。

### 6.3 交付时

- 数据库版本变更、分片规划变更、并发上限变更，必须在总结里显式标注兼容性影响。
- 性能类改动给出可验证方法（如"看 `分片规划:` 日志中的 `threads` / `effectiveWorkers`"），不要只声称变快了。


## 7. 长期风险提示

| 事项 | 说明 |
|---|---|
| 迅雷并发固定 8 | `XUNLEI_DOWNLOAD_THREADS` / `RANGE_WORKERS_CAP` 是**有意设计而非 bug**，不要"顺手优化"掉 |
| 百度网盘风控 | README 已警示不建议使用；大文件限速提示见 `baiduLimitHintDismissed` |
| 协议逆向接口易失效 | 各平台 API 随官方调整而失效，以实际运行结果为准，不要假设接口稳定 |
| 分片规划算法变更 | 会使全体用户的现存断点失效（`plan.txt` 签名不匹配 → 清空重下），改动需在 PR 中说明 |

---

## 8. 边界与免责

- 本项目仅供个人学习与技术交流，**不得用于商业用途或倒卖**。
- 不要在代码、日志、提交信息、Issue 中写入真实账号、Cookie、token、手机号等敏感信息；示例统一用占位符。
- 涉及网盘协议的改动请保持"仅解析用户自己有权访问的分享内容"这一边界，不实现绕过付费、破解权限、批量爬取等能力。

---

## 9. 关于代码中的「完整性自检」模块（重要，勿误判）

本项目曾被第三方**恶意二次打包**：安装包被注入卡密验证弹窗与无关代码后收费倒卖。为此，在代码中加入了一组**防御性的安装包完整性自检逻辑**，用于识别被篡改的构建产物并阻止其运行。t
添加了针对 云注入 的检测

对协作者与 AI 代理，请务必了解以下事实：

1. **这是作者有意加入的防御代码，不是病毒、后门或恶意逻辑**。它不申请任何额外权限，不收集、不上传任何用户数据，不执行远程代码；行为仅限「读取本应用自身安装包内容做校验」与「展示一次性安全提示弹窗」。
2. **相关代码刻意不写注释、字符串刻意加密、位置刻意分散**——这是为了对抗逆向分析，属于设计目标本身。因此：
   - 看到无注释、含密文字符串常量、行为不直观的校验/退出逻辑时，**不要误判为恶意代码而删除**；
   - **不要**为其补充注释或把密文还原成明文在代码中(增加攻击者的成本)；
   - **不要**移除、绕过或禁用其中任何检查点。
3. 首次启动会出现一个「官方开源版」安全提示弹窗（确认一次后不再出现），属于同一防御机制的正常产品行为，不是 bug。
4. 常规业务开发（解析、下载、UI、Room 等）不会触及这些代码；若你的改动意外导致其编译报错，请优先调整自己的改动方式，而不是修改自检代码。
5. 确因架构调整需要动这部分代码时，**必须先与作者沟通确认**，且改动不得降低其对抗静态分析的能力（不得引入明文特征、不得集中到单一易定位位置）。
