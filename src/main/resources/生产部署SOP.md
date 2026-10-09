# 生产部署 SOP

知乎草稿创建与已有草稿公开发布共用数据库保存的浏览器登录态。部署时按下列顺序检查；分别见 [浏览器登录配置](config/browser-login.md)、[知乎公开发布配置](config/zhihu-publish.md)。

## 1. 准备运行环境

1. 部署基线使用 **Microsoft OpenJDK 17.0.17（LTS）**，与当前开发机和 `GeoBoot` 运行环境一致。安装后执行 `java -version`，确认显示 `17.0.17`。开发机的 `JAVA_HOME` 为 `/Users/huangry/Library/Java/JavaVirtualMachines/ms-17.0.17/Contents/Home`；生产机按实际安装路径设置，不照搬此路径。
2. 如果需要知乎草稿能力，按 [浏览器登录配置](config/browser-login.md) 安装 Playwright Chromium、初始化登录表，并完成知乎登录。
3. 如果需要把已有知乎草稿公开发布，确认数据库保存的知乎登录态有效；无需另外准备 Chrome 登录资料目录。
4. 确认 Java 服务能访问模型网关；知乎发布节点还要能访问知乎，且 Playwright Chromium 可以运行。

## 2. 配置应用

1. 在外置配置中设定 `colorful.geo.llm.enabled_llm_provider`，填写该通道的 `base-url`、`model` 和 `api-key-env`。
2. 给 Java 服务进程设置 `api-key-env` 所指向的密钥环境变量。当前 custom 通道使用 `COLORFUL_GEO_LLM_CUSTOM_API_KEY`。
3. 其他平台草稿能力按各自独立浏览器登录通道接入；知乎草稿不需要额外配置。
4. 如启用知乎公开发布，按 [知乎公开发布配置](config/zhihu-publish.md) 检查数据库登录态；仅在需要时调整页面动作超时。
5. 打包前移除源码中的真实 Token 和 API Key，凭证只通过受控的运行环境或外置配置提供。不要把 `/test/**` 测试接口暴露到公网。

## 3. 启动与验证

1. 构建并确认部署产物可启动。当前 POM 未显式配置 Spring Boot 可执行 JAR 的 `repackage`，不要直接假定普通 JAR 可用 `java -jar` 启动。
2. 如果启用知乎草稿，先确认数据库中的知乎 Playwright 登录态有效，再启动 Java 服务；当前按单实例发布节点部署。
3. 验证大模型查询与内容优化。知乎草稿先在浏览器登录管理中检查登录，再创建一篇测试草稿。
4. 确认返回 `DRAFT_CREATED` 和 `draftUrl`，打开链接核对内容；超时后先检查草稿箱，再决定是否重试。
5. 公开发布链路只在业务审核完成后调用 `publishDraft`；若返回 `PUBLISHING`，人工核对，不自动重试。

## 上线前核对

- [ ] Microsoft OpenJDK 17.0.17、所选模型配置和密钥齐全，网络可达。
- [ ] 使用知乎草稿时，完成 [浏览器登录与 Playwright 环境准备](config/browser-login.md)。
- [ ] 使用知乎公开发布时，按 [知乎公开发布配置](config/zhihu-publish.md) 确认数据库会话有效。
- [ ] 源码和制品不含真实凭证，测试接口未对公网开放。
- [ ] 部署产物可以启动，Java 的实际草稿创建验证成功。
