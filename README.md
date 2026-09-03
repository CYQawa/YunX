# YunX（云析）

网盘分享链接解析与高速下载的 Android 应用。粘贴分享链接，就能浏览分享内容并直接下载文件。

## 支持平台

**不建议用百度网盘，可能导致账号被风控！！！**
- 夸克网盘
- UC 网盘
- 迅雷网盘
- 百度网盘
- 123云盘
- 139 网盘（和彩云）
- 123 云盘
- ~~其他网盘懒得写了，如有需要请打开一个issue~~

## 功能

- **分享链接解析**：识别夸克 / UC / 迅雷 / 百度 / 139 / 123 的分享链接，自动匹配提取码
- **高速下载**：Range 分片并发 + 断点续传，任务保存请求头与固定分片规划，并发上限 32
- **临时转存清理**：百度/迅雷取链后清理；夸克保留到下载完成或删除任务后清理
- **登录**：夸克 / UC / 百度 / 139 使用 WebView Cookie；迅雷使用密码/短信；123 使用账号密码换取 JWT
- **认证备份**：使用用户口令派生密钥，以 AES-GCM 加密 Cookie/JWT 备份文件
- **剪贴板识别**：复制分享链接后回到应用，提示一键粘贴解析

## 截图

| 解析直链 | 分享解析 | 下载管理 |
|:---:|:---:|:---:|
| ![解析输入](images/Link.jpg) | ![文件列表](images/Parsing.jpg) | ![下载管理](images/Download.jpg) |

| 网盘登录 | 设置 | 关于 |
|:---:|:---:|:---:|
| ![网盘登录](images/Login.jpg) | ![设置](images/Setting.jpg) | ![关于](images/about.jpg) |

## 使用

1. 在「网盘」页登录需要用的网盘账号
2. 在「解析」页粘贴分享链接（可带提取码）
3. 浏览分享内容，点击文件获取下载直链
4. 「下载」页查看进度，支持暂停 / 继续 / 删除 / 打开

## 技术栈

- Kotlin
- Jetpack Compose + Material 3
- Room（凭证与下载任务持久化）
- OkHttp（网络请求 + 分片下载）
- KSP

## 构建

要求：minSdk 21，targetSdk 34。

```
git clone https://github.com/CYQawa/YunX.git
```

用 Android Studio 打开项目直接构建即可。项目在 AndroidIDE 上开发调试，理论上也兼容其它 Android 构建环境。

## 免责声明

本项目仅供个人学习与技术交流，请勿用于商业用途。下载内容版权归原作者所有，请在下载后 24 小时内删除。使用本项目产生的任何后果由使用者自行承担。

## 开源协议

本项目基于 [GNU AGPL-3.0](https://www.gnu.org/licenses/agpl-3.0.html) 协议开源，详见根目录 [LICENSE](./LICENSE)。

## 关于协议逆向

部分网盘平台的解析基于抓包分析与开源项目（如 alist）的协议研究整理，接口可能随官方调整而失效，请以实际运行结果为准。

# 耻辱榜
**倒卖的你是活不起了是吗😂**
* 注：云析完全免费开源，如果你下载到要钱的，那么你就是被骗了，请立马去退款

- 倒卖狗🐶：qq1360735243

## 应用自我保护说明

由于本项目曾遭遇恶意二次打包（注入卡密验证弹窗后收费倒卖），代码中加入了一组**安装包完整性自检逻辑**，针对云注入的检测，用于识别并阻止被篡改的安装包运行。在此向用户与协作者说明：

- 该逻辑为**防御性设计**：不申请额外权限，不收集、不上传任何个人数据，不影响任何正常功能；
- 首次启动会展示一次「官方开源版」安全提示，确认后不再出现；
- 这部分代码刻意不写注释、字符串加密存放，属于对抗逆向篡改的设计需要，**并非后门或恶意代码**，请勿误解；
- 详见 [Agent.md](./Agent.md) 第 9 节。

再次强调：**云析永远免费开源，任何收费版本均为诈骗**。

## Star History

[![Star History Chart](https://api.star-history.com/chart?repos=CYQawa/YunX&type=date&legend=top-left&sealed_token=hccCg_4ek01_Sz38X79eMbjM11mNpOZti6_hLoztWW4Zdtx-8FScydd7YTdiCBUWvgpsuGDO70RrUKP-bOfbI3Gw8BnME1zIl5EHA9JWsv--_DDwWPjvKbZiAGNDslG3ZTDZ-Ssiapu7j08W4fPT6emGWaIIuawHoIw3Nic_xQu7hUSVO6_YeJRGRoEy)](https://www.star-history.com/?repos=YunX%2FYunX%2CCYQawa%2FYunX&type=date&legend=top-left)