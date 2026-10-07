# Wechatsync 部署配置

当前用于知乎以外已接入的浏览器平台草稿；知乎草稿已改用数据库保存的 Playwright 登录态。使用 Wechatsync 的平台仍要求 Java、CLI 与 Chrome 扩展部署在同一台可保持浏览器运行的机器上。知乎已有草稿的公开发布另见 [知乎发布配置](zhihu-publish.md)。

## 1. 安装软件和插件

1. 安装 Node.js 和 npm，使用已联调验证的版本。
2. 安装 Google Chrome。
3. 在 Chrome 中从 [Wechatsync 官方项目](https://github.com/wechatsync/Wechatsync)提供的入口安装并启用「文章同步助手」扩展。
4. 安装固定版本 CLI：

```bash
npm install -g @wechatsync/cli@1.1.0
node --version
npm --version
wechatsync --version
command -v wechatsync
```

记下 `command -v wechatsync` 返回的绝对路径。`/opt/homebrew/bin/wechatsync` 只是开发机路径，部署机应使用自己的安装路径。Java 进程的 `PATH` 必须包含 Node 所在目录。

## 2. 配置浏览器和知乎账号

1. 打开 Chrome，在文章同步助手扩展中开启同步桥接，获取或设置桥接 Token。
2. 在同一 Chrome 用户资料中登录知乎，确认是用于投放的账号。
3. 保持 Chrome 和扩展运行，机器不要休眠。

桥接 Token 用于 CLI 连接扩展，不能代替知乎登录。

## 3. 配置 Java 服务

在部署使用的外置配置中增加：

```yaml
colorful:
  geo:
    wechatsync:
      token: ${WECHATSYNC_TOKEN}
      executable: ${WECHATSYNC_EXECUTABLE}
      connection-timeout-millis: 30000
      auth-timeout-seconds: 45
      timeout-seconds: 120
```

给**启动 Java 的进程**提供这些环境变量：

| 环境变量 | 内容 |
| --- | --- |
| `WECHATSYNC_TOKEN` | 第 2 步扩展使用的同一 Token |
| `WECHATSYNC_EXECUTABLE` | 第 1 步查到的 CLI 绝对路径 |
| `PATH` | 包含 Node 所在目录 |

配置对应类是 `infrastructure/client/wechatsync/WechatsyncProperties.java`。该类的 `token` 非空时优先使用配置值，否则读取 `WECHATSYNC_TOKEN` 环境变量。打包前清空源码中的真实 Token，并使用外置配置注入生产凭证；在其他终端设置变量不会影响已经启动的 Java 进程。

## 4. 检查扩展和登录

在配置了同一 Token 的终端执行，避免与 Java 发布任务同时运行：

```bash
wechatsync --timeout 30000 auth zhihu
```

确认输出显示 `Chrome Extension 已连接`、`zhihu 已登录`，且账号正确。此检查不创建草稿，也不会代替浏览器扫码登录。Java 服务在每次同步前使用同一 Token 自动执行这项检查；检查失败时不提交正文。Chrome 休眠或扩展断开后仍需恢复浏览器连接。

## 5. 验证

1. 先启动 Chrome 与扩展，再启动 Java 服务。
2. 通过受控的应用入口提交一篇标注“部署测试，请勿公开发布”的短文本。
3. 确认返回 `DRAFT_CREATED`、远程内容 ID 和 `draftUrl`，打开编辑链接核对草稿。
4. 再用接近实际业务长度的正文验证一次。

Wechatsync 同步动作不会公开发布；公开发布由独立的 `publishDraft` 完成。图片正文不再被策略提前拦截，实际上传结果应在草稿箱核对；本地相对图片路径可能因临时 Markdown 文件位置变化而失效。同步超时不代表平台没有创建草稿；先检查草稿箱，再决定是否重试。桥接端口 `9527` 不要对公网开放。
