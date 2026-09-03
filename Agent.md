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
| minSdk / targetSdk / compileSdk | 23 / 34 / 36 |
| JVM target | 17 |
| 开源协议 | GNU AGPL-3.0 |

支持平台：夸克、UC、迅雷、百度、139（和彩云）、123 云盘。

---

## 2. 目录结构与职责

```
app/src/main/kotlin/com/yunx/app/
├── MainActivity.kt              # 单 Activity 入口
├── YunXApp.kt                   # Application，全局初始化
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
并发 worker = effectiveWorkers（信号量 Semaphore 固定容量，绝不手动 release）
```

- worker **循环领片**，慢片不阻塞其他线程 → 根治"尾部并发塌缩"。
- 弹性区用 `ElasticAllocator` **按字节顺序**分配，替代早期的"中点劈分"（劈分会导致主池耗尽瞬间全部线程涌入、区间跨度翻倍、连接复用率崩塌 → 中后段掉速）。

### 5.2 `chunkCountFor` 的真实语义（易被误读）

```kotlin
val minChunkBytes = 1 * 1024 * 1024L      // 「单片最小 1MB」= 分片数上限阀，不是"每片就是 1MB"
val bySize = when {                        // 按文件大小的基础分片数
    total < 5MB -> 1;  total < 50MB -> 8;  total < 500MB -> 32;  else -> 64
}
val want = maxOf(bySize, threads * 8)      // 每线程平均 8 片盈余
return minOf(want, (total / minChunkBytes).toInt(), 512)   // 512 为硬封顶
```

**实际单片大小 = `ceil(total / chunkCount)`**，并非固定 1MB——大文件的单片远大于 1MB，线程数越高、分片数封顶后单片越大。

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
