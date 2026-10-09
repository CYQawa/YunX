# Material 3 Expressive 修复与验收计划

基线：f7296044f88e96b9ec507ac9026418ef89fc454c。对应两轮设计审查；保留现有依赖版本、导航架构和业务接口。

| 范围 | 修改内容 | 验收方式 |
| --- | --- | --- |
| 颜色 | 状态徽章使用主题颜色角色；种子色上的图标自动选择黑白前景 | 默认与浅色种子，文字 ≥4.5:1、图标 ≥3:1 |
| 排版与适配 | 恢复标题/正文刻度；按窗口宽高选择导航；选择项随字体增长 | 320/600/840dp、200% 字体、横屏与分屏 |
| 选择语义 | 设置整行 Switch 语义、单选组和色圆名称/选中态 | Compose 语义断言、TalkBack 验收 |
| 调色盘 | 增加可访问 Slider、HEX 名称与键盘操作，色相条预留 48dp | 无触摸完成调色；键盘与小屏验收 |
| 动效与进度 | 尺寸和透明度使用对应 spring token；波形不被压缩裁剪 | 组件布局与状态回归 |
| 文件操作 | 忙碌时在隐藏前拦截；禁用返回/重复操作；表单可滚动；重命名成功后关闭、失败保留 | 忙碌时遮罩/返回/下滑、失败重试、横屏滚动 |
| 多选 | 零选中和处理中禁用操作，按钮提供 Tooltip | 零选中不触发动作；选中后正常触发 |
| 状态反馈 | Snackbar 队列，最近挂载宿主消费、宿主切换保留事件 | 连续三条、并发发送、宿主切换、重复消息 |
| 登录 | 服务错误与字段错误分开；密码键盘和错误播报 | 网络失败不误标字段，输入与重试正常 |
| 复制 | 下载直链增加明确复制按钮及长按标签 | 点击复制并显示成功提示 |

## 自动化验证

- 编译 Debug APK 与 androidTest APK。
- 运行已有单元测试，增加消息队列/宿主切换和窗口选择的回归测试。
- 增加 Compose 仪器测试：开关/单选语义、忙碌弹窗、零选中工具栏、重命名重试、大字体。
- 运行 Android Lint，并记录基线与新增问题。
- 仪器测试需要可运行的 Android 设备；如执行环境不支持模拟器，明确记录已编译但未执行的项目，不能视作通过。

## 交付

测试结果写入本文件；PR 描述说明修复范围、验证命令及设备验收限制。没有下载引擎、数据库迁移或凭证加密改动。

## 审查依据与边界

- [Material 3 颜色角色](https://m3.material.io/styles/color/roles)：用容器色及对应前景角色，适配浅色、深色和动态色。
- [Material 3 动效](https://m3.material.io/styles/motion/overview)：空间变化与效果变化使用对应规格。
- [Compose 默认无障碍行为](https://developer.android.com/develop/ui/compose/accessibility/api-defaults)：可操作区域至少 48dp；自定义组件提供角色、名称、选中态和调节动作。
- 波浪进度、面板状态与 Tooltip API 按项目固定的 `material3:1.5.0-alpha18` 源码核对，不调整依赖版本。

排版刻度和 600dp 导航断点属于本应用的实现选择。自动化结果用于证明此次修复；真机 TalkBack、系统动态色、多窗口和完整视觉验收仍需逐项记录，不能据此声称整个应用已经获得规范认证。

## 实施与验证结果（2026-10-07）

表中修复均已实施，并补齐原有 `SettingsDirectoryPickerTest` 的三个 DAO 参数和 `onGopeedClick` 测试回调。保留 Kotlin、Compose BOM 和 Material3 版本，不新增依赖。

| 检查 | 实际结果 |
| --- | --- |
| `assembleDebug` | 通过，生成 `app/build/outputs/apk/debug/app-debug.apk` |
| `testDebugUnitTest` | 68 项通过，0 失败、0 错误；新增 7 项回归测试 |
| `assembleDebugAndroidTest` | 通过，新增 6 项 Compose 交互测试已编译 |
| `lintDebug` | 未通过：147 错误、170 警告、23 提示 |
| Compose 仪器测试运行 | 未执行完成：当前环境没有 KVM，软件模拟器首次启动出现系统服务重启；后续设备命令被自动审批服务额度错误阻止 |
| `git diff --check` | 通过 |

Lint 的全部错误位于未修改文件：`Theme.kt` 142 项 RestrictedApi、`DiagnosticWebViewClient.kt` 1 项 RestrictedApi、`CredentialCipher.kt` 1 项 NewApi、`BaiduApi.kt`/`XunleiApi.kt` 3 项 SuspiciousIndentation。这些文件与基线相同。未创建 baseline 或屏蔽诊断；没有改动凭证加密或安全自检。此次修改新增两条 ConfigurationScreenWidthHeight 警告，建议后续将导航尺寸来源迁移到 `LocalWindowInfo`；目前保留已经构建验证的 Configuration 实现。

构建使用 JDK 17、Gradle 9.0.0、compileSdk 36 和 build-tools 35.0.0。受环境 8GiB 内存和 2 核 CPU 限制，使用以下选项限制并发：

```sh
YUNX_USE_MIRROR=false ./gradlew --no-daemon --max-workers=2 --no-parallel \
  -Pkotlin.compiler.execution.strategy=in-process \
  assembleDebug testDebugUnitTest assembleDebugAndroidTest lintDebug
```

该组合命令最终因 Lint 失败退出；前三项的构建/测试结果由任务日志及 JUnit XML 单独确认。仓库要求的 `~/.yunx-bal/ktcheck.py`、`impcheck.py`、`vischeck.py` 在此环境不存在，无法执行。

### 待设备验收

- 在可用设备上运行 `./gradlew connectedDebugAndroidTest`：开关与单选语义、200% 字体、调色 Slider 无障碍动作、工具栏禁用状态、面板隐藏拦截、重命名失败重试。
- 真机检查忙碌状态下的遮罩点击、返回与拖拽；失败提示出现在面板内，重试输入保留。
- 320dp 紧凑窗口、600/840dp 宽窗口、紧凑高度横屏及分屏；浅色、深色、动态色、TalkBack 与键盘调色。
- 单元测试验证了颜色前景的亮度对比算法；不代替整页实际渲染的视觉对比与可访问性验收。
