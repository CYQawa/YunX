<img src="https://capsule-render.vercel.app/api?type=waving&color=gradient&height=300&section=header&text=YunX&desc=%E4%BA%91%E6%9E%90%20%C2%B7%20%E7%BD%91%E7%9B%98%E5%88%86%E4%BA%AB%E9%93%BE%E6%8E%A5%E8%A7%A3%E6%9E%90%E4%B8%8E%E9%AB%98%E9%80%9F%E4%B8%8B%E8%BD%BD&fontSize=64&fontColor=ffffff&descSize=16&animation=fadeIn&fontAlignY=40&descAlignY=55" />

[![QQ交流群](https://img.shields.io/badge/QQ%E7%BE%A4-635207650-12B7F5?style=flat-square&logo=qq&logoColor=white)](http://qm.qq.com/cgi-bin/qm/qr?...&group_code=635207650)

# YunX（云析） v1.3.0

## 更新内容

### 新增
- 新增视频链接解析（7 家）：抖音 / 小红书 / 快手 / 哔哩哔哩 / 微博 / AcFun / Pixiv，分享链接直接解析
- 视频结果支持在线预览与下载：图文笔记按两列直接铺开原图（可放大、可多选、可整组下载）；视频在结果页内嵌播放器，默认不自动播放
- 新增视频平台账号：各平台可在应用内登录；登录后才能解析全部作品（Pixiv 等受限内容尤其需要登录，也支持手动填入 Cookie）
- 新增网盘多账号：同一个网盘可登录多个账号、加备注、设主账号；批量下载时按文件轮换账号取链，绕开单账号的并发与限速限制
- 新增单文件多源分片（实验性）：同一个文件的多个分片由不同账号并行拉取，进一步提速（风控风险更高，默认关闭）
- 新增链接唤起：分享链接可直接交给云析打开，不用先复制再粘贴
- 新增下载调试面板：按任务查看完整日志、线程调试页、分片级校验
- 新增友情链接（关于页）
- 凭证密钥加固；欢迎页网盘数量与认证备份补齐平台

### 修复
- 修复设置页二级页面（如「多账号下载」）缺少容器变换动画：打开 / 关闭时重新有「整行长成整页」的形变
- 账号页版式统一：网盘与视频平台统一为「一个平台一张卡片」，图标尺寸 / 卡片边距 / 字号对齐
- 支持页赞赏卡片：顶牌居中、底牌从侧面露出并缩小一圈，「近大远小」层次更清楚
- 修复迅雷应用内网页登录页渲染不全
- 修复 Material 3 Expressive 设计审查发现的一批细节问题

## 通知
* 由于作者还是初中生，开学住校后仅休息日可查看消息，开学后更新速度会减缓，但不会停止维护，望理解


**Full Changelog**: https://github.com/CYQawa/YunX/compare/v1.2.9...v1.3.0