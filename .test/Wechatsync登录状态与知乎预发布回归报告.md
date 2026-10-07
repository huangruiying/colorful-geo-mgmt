# Wechatsync 登录状态与知乎预发布回归

测试时间：2026-10-01（本机 macOS）。仅验证平台登录状态查询与知乎预发布；未执行公开发布。

## 启动与环境

- IDE 配置来源：`../.idea/workspace.xml` 的 `GeoBoot` Spring Boot 配置；项目 SDK 为 `../.idea/misc.xml` 中的 `ms-17`。
- 实际 JDK：`/Users/huangry/Library/Java/JavaVirtualMachines/ms-17.0.17/Contents/Home`；Maven：`/Users/huangry/Documents/maven/apache-maven-3.6.1/bin/mvn`。
- 启动类：`org.huangry.colorful.geo.GeoBoot`；工作目录为本模块；监听 `http://127.0.0.1:8010`。
- 本地 `colorful-geo-mysql` 容器健康。测试服务使用编译后的模块 classpath 启动，PID 为 81375；日志采集为本次启动终端输出。

## 原因与修复

1. 初始未监听 9527；带同一桥接 Token 的非交互 `platforms --auth` 与 `auth zhihu` 均等不到扩展连接。Chrome 扩展已安装、MCP 已启用，扩展存储的 Token 与 Java 配置一致，故不是缺 Token 或 CLI 参数错误。
2. 本机已安装扩展的后台代码采用 WebSocket 主动连接 `ws://localhost:9527`；冷态重连间隔会增长。CLI 只监听并等待连接，不会主动唤醒休眠的扩展后台。冷态超时后 CLI 进入交互安装提示，Java 进程关闭标准输入，最终只能按外层超时结束。
3. 在 CLI 等待期间打开文章同步助手扩展页面，约 9 秒内连接成功并返回知乎已登录。因此修复仅在只读登录查询超时时于 macOS 后台打开扩展页面，再查询一次；正文 `sync` 不自动重试，避免重复草稿。

## 真实接口结果

| 场景 | 请求 | 结果 |
|---|---|---|
| 冷态平台登录状态 | `GET /publication/platforms/login-status` | HTTP 200；约 50 秒；28 个平台，知乎 `LOGGED_IN`，账号“见微”；首次超时后自动唤醒并重查成功 |
| 知乎预发布 | `POST /test/prePublish/ZHIHU` | HTTP 200；约 3 秒；`DRAFT_CREATED`，草稿 ID `2089022292982608261`，编辑链接 `https://zhuanlan.zhihu.com/p/2089022292982608261/edit` |
| 温态平台登录状态 | `GET /publication/platforms/login-status` | HTTP 200；约 6 秒；知乎仍为 `LOGGED_IN` |

定向单测：`WechatsyncClientTest` 11 个、`WechatsyncLoginStatusTest` 6 个，共 17 个通过。未运行其他业务接口或公开发布动作。

## 保留数据与限制

- 知乎测试草稿保留在账号草稿箱，标题为“GEO 桥接联调草稿（请勿公开发布）2026-10-01 16:03”；未清理、未公开发布。
- 自动唤醒依赖 macOS、Google Chrome 与当前文章同步助手扩展 ID。无桌面浏览器的 Linux/容器生产环境不适用该唤醒方式，仍需独立的常驻浏览器与扩展连接方案。
- 未使用 mock、临时业务分支或登录绕过；单测中的 `/bin` 替身进程仅存在于测试代码。
