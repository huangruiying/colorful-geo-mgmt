# 生产部署 SOP

容器部署见 [deploy/README.md](../../../deploy/README.md)，Compose 自动构建应用、启动 MySQL 并初始化空库。以下手动环境准备仅用于非容器部署。

知乎草稿创建与已有草稿公开发布共用数据库保存的浏览器登录态。部署时按下列顺序检查；分别见 [浏览器登录配置](config/browser-login.md)、[知乎公开发布配置](config/zhihu-publish.md)。

## 1. 准备运行环境

1. 部署基线使用 **Microsoft OpenJDK 17.0.17（LTS）**，与当前开发机和 `GeoBoot` 运行环境一致。安装后执行 `java -version`，确认显示 `17.0.17`。开发机的 `JAVA_HOME` 为 `/Users/huangry/Library/Java/JavaVirtualMachines/ms-17.0.17/Contents/Home`；生产机按实际安装路径设置，不照搬此路径。
2. 如果需要知乎草稿能力，按 [浏览器登录配置](config/browser-login.md) 安装 Playwright Chromium、初始化登录表，并完成知乎登录。
3. 如果需要把已有知乎草稿公开发布，确认数据库保存的知乎登录态有效；无需另外准备 Chrome 登录资料目录。
4. 确认 Java 服务能访问模型网关；知乎发布节点还要能访问知乎，且 Playwright Chromium 可以运行。

## 2. 配置应用

1. 空库执行 `db/mysql/001_init.sql`，建立包括共享模型渠道表在内的全部表。
2. 在“基础配置 → 模型配置”填写渠道、模型、接口地址和 API 密钥，保存到 `geo_llm_channel`。未指定渠道或模型时，按已启用渠道的 `score` 降序、ID 升序选择；模型配置不再从 YAML 读取。新库需先新增并启用至少一个可用渠道。
3. 其他平台草稿能力按各自独立浏览器登录通道接入；知乎草稿不需要额外配置。
4. 如启用知乎公开发布，按 [知乎公开发布配置](config/zhihu-publish.md) 检查数据库登录态；仅在需要时调整页面动作超时。
5. 源码及制品不包含真实 Token 和 API Key；模型凭据由数据库维护，查询接口不返回密钥。不要把管理接口或 `/test/**` 测试接口暴露到公网。

## 3. 启动与验证

1. 构建并确认部署产物可启动。容器 Dockerfile 显式执行 `spring-boot:repackage`；手动部署可执行 `mvn -DskipTests package spring-boot:repackage`。
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
