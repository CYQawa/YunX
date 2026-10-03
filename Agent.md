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
    └── theme/                   # Color / Type / Theme / ThemeController
```

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

`AppDatabase.kt` 现为 **version = 13**。新增表/字段的流程：

1. `entities` 数组追加 Entity
2. `version` +1
3. 新增 `abstract fun xxxDao()`
4. 写 `MIGRATION_N_N+1`（新增表用 `CREATE TABLE IF NOT EXISTS`，不动旧表）
5. 注册到 `.addMigrations(...)`

`fallbackToDestructiveMigrationFrom(1..8)` 仅适用于早期开发版；**v9 起必须保留用户凭证与下载任务**。

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
- 已接入：`ShareFileRow`（解析页 + 六个网盘页 + 转存/移动选择器的**唯一行组件**）、
  六个 `*SaveSheet`/`SaveToCloudSheet` 的文件名头、`CloudFileSheets` 文件详情头、`DownloadScreen` 的任务行/分组行。
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

**结论**：解析分享**不再要求登录**。6 个网盘的分享**列表**接口都允许匿名访问（用户实测：浏览器未登录也能列出文件）；
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

## 4. 验证


写完代码后逐项自查，然后交付：

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
| 图标找不到 | 已引入 `material-icons-extended`，确认图标名与 `Outlined`/`Filled` 命名空间 |
| Room 编译报 schema 错 | 检查 `version` 是否 +1、Migration 是否注册 |

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
