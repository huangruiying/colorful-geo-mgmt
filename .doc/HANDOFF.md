# Project Handoff

> 核对日期：2026-10-07，仓库 `/Users/huangry/Documents/huangry-code/colorful-geo-mgmt`。本文件是协作快照，不是聊天记录或平台联调成功证明。接手者先复核 Git、代码与数据库状态。共同规则见仓库根目录 [AGENTS.md](../AGENTS.md)。

## 1. 项目概述与当前目标

本项目是独立的 GEO 内容管理服务：录入内容、调用大模型优化、保存优化记录、确认最终稿、逐平台预发布草稿，并记录状态与平台返回值。`prePublish` 是创建草稿；`publish` 是将已有草稿公开发布，不能混为一谈。

**当前开发主线**：把仍通过 Wechatsync CLI 预发布的平台逐步迁移到自有 Playwright 客户端，并使用数据库保存的浏览器登录会话。用户要求优先完成已有登录态的平台，不能只改一两个便宣称“全改”。每个平台应取得真实可核验的草稿结果；没有有效登录态、账号权限或平台成功信号时，明确保留未完成状态。截至本次，简书已按 CSDN/知乎模式完成预发布代码迁移与单元测试，真实平台联调待已登录会话验证；其余仍走 Wechatsync 的已登录平台逐个进行。

## 2. 技术栈与项目结构

| 项目 | 当前仓库事实 |
|---|---|
| 构建 | 独立 Maven 工程；根目录 `pom.xml` 的 artifactId 为 `colorful-geo-mgmt`，版本 `1.0.0-SNAPSHOT`，Spring Boot Maven 插件已配置。不是原 `colorful-source` 多模块工程。 |
| 运行 | Java 17、Spring Boot 3.2.0；入口 `org.huangry.colorful.geo.GeoBoot`；默认 HTTP 端口 8010。 |
| 数据 | MySQL、MyBatis-Plus 3.5.17；SQL 在 `src/main/resources/db/mysql/`，本地容器说明见 `docker/README.md`。 |
| 浏览器/AI | Playwright Java 1.58.0；LangChain4j 1.10.0；另有 Lombok、Fastjson、WhatsAppWeb4j 依赖。依赖存在不代表对应平台能力已联调。 |

- `application/content/` 编排优化与发布记录；`domain/service/` 承载业务服务。
- `infrastructure/client/login/` 处理浏览器登录；`infrastructure/client/playwright/` 管理浏览器任务；`infrastructure/client/publish/browser/` 执行平台页面动作；`infrastructure/client/publish/strategy/browser/` 路由平台策略。
- `infrastructure/client/wechatsync/` 是尚有引用的旧通道；`infrastructure/repository/` 与 `entity/` 是持久化层。
- `presentation/controller/` 暴露接口；`src/main/resources/static/admin/` 是同服务端口的管理页。
- 详细部署与设计以 `src/main/resources/生产部署SOP.md`、`src/main/resources/config/`、`.doc/` 和当前代码为准；发现旧路径或旧类名时先核实，不照搬。

## 3. 当前进度

### 已完成（代码层面）

- 知乎已使用数据库 Playwright 会话实现草稿创建与已有草稿发布的浏览器客户端。**本次交接未重新执行真实公开发布验证**。
- CSDN、微信公众号已具有自有浏览器客户端和预发布策略；历史联调曾独立创建并回读测试草稿。它们的实际公开发布仍未接入，历史实验不能代替当前 Java 服务端到端验证。
- 简书已完成预发布代码迁移：新增 `JianshuBrowserClient`（恢复 DB 登录态、建草稿、重开回读）与 `JianshuPublicationStrategy`（改 `extends AbstractPublicationStrategy`，弃用 Wechatsync 基类），并补 `JianshuBrowserClientTest`/`JianshuPublicationStrategyTest` 单元测试。编辑器选择器依据简书写作页结构与既有实验（note id `144599844`）推断，未经过本次已登录会话实测，标注“待真实联调”。真实联调前不得宣称草稿成功。
- **百家号、掘金、今日头条、微博预发布代码迁移已完成**（2026-10-07 晚，未提交）：各自新增 `XxxBrowserClient`（恢复 DB 登录态、建草稿、回读校验）与改写 `XxxPublicationStrategy`（改 `extends AbstractPublicationStrategy`，弃用 Wechatsync 基类），并各补 `XxxBrowserClientTest`/`XxxPublicationStrategyTest` 单元测试。选择器来自 `.doc/平台发文入口与选择器.md` 的真实只读探索；草稿 ID 提取与回读校验对百家号/今日头条/微博采用页内软校验（草稿 URL 形态待真实联调确认），掘金按 URL 跳转 `/editor/drafts/{id}` 回读。全部标注“待真实联调”，真实联调前不得宣称草稿成功。
- 迁移后项目的根 `pom.xml` 已使用 Spring Boot starter parent、独立 artifactId 和 Spring Boot Maven 插件；本次不改 POM 或业务代码。
- **小红书已按自管模式重新接入（2026-10-09 晚，未提交）**：与用户确认"全面剔除 Wechatsync 后恢复小红书"后，新增 `XiaohongshuBrowserClient`（恢复 DB 登录态、进创作中心→发布笔记→写长文→新的创作、填标题 textarea 与正文 ProseMirror、离开编辑器前页内回读、`暂存离开` 存草稿）+ 改写 `XiaohongshuPublicationStrategy`（`extends AbstractPublicationStrategy`，`platformType()=XIAOHONGSHU`，`execPublish` 暂抛"小红书草稿公开发布暂未接入"）；并补 `XiaohongshuPublicationStrategyTest`。登录态走 `platform_browser_login`（须 `LOGGED_IN`），与 8 个已自管平台一致。长文草稿 id 进 URL 与否待真实联调确认，无法提取时 `remoteContentId` 返回 null（与微博一致）。`mvn -o clean test` 全量 **216** 通过（原 215 + 新增 1）；`TestControllerPublicationTest` 已覆盖 `XIAOHONGSHU` 路由返回 `DRAFT_CREATED`。**长文"发布"按钮仍待真实联调确认，发布能力未实现**。
- **「平台登录状态」管理页已清理并入「浏览器登录管理」（2026-10-09）**：原 `admin/index.html` + `login-status.js` 是 Wechatsync 时代的只读登录态展示，与 `browser-login.html`（真正的浏览器登录管理后台，遍历全部平台、可登录/登出/最近检测）功能重叠且文案过时（残留"请检查 Chrome、文章同步助手及桥接连接"）。已彻底清理：删除 `index.html`/`login-status.js` 与前端点 `/publication/platforms/login-status`、`PublicationPlatformLoginService.listPlatformLoginStatuses()`、`PlatformLoginStatus` 模型，删除 `PublicationPlatformLoginServiceTest` 整文件并移除 `PublicationPlatformControllerTest` 的 login-status 分支；`contents.html`/`publications.html`/`browser-login.html` 的"平台登录状态"导航项统一并入"浏览器登录管理"，`GeoBoot` 入口日志改为 `browser-login.html`。`PublicationPlatformLoginService` 仅保留 `listConfiguredPlatforms()`（发布页选项仍依赖 `/publication/platforms`）。`mvn -o clean test` 全量 **213** 通过（216 − 3 个被删用例）。

### 进行中 / 待验证

- ~~当前代码中仍有 19 个具体策略继承 `AbstractWechatsyncPrePublicationStrategy`~~ **（已解决，2026-10-09）**：Wechatsync 通道已于 2026-10-09 全面剔除，19 个 Wechatsync 平台策略子类与基础设施全部删除；仅保留 9 个自管 Playwright 平台（知乎、百家号、掘金、今日头条、微博、简书、微信公众号、CSDN、小红书）。小红书为本次新按自管模式重新接入（见上方"已完成"）。其余 B 站、搜狐号、抖音等 18 个平台仍待按需自实现，不等于都有有效登录态或可以发文。
- 2026-10-07 14:24 的历史数据库快照曾显示 11 个 `LOGGED_IN` 平台：百家号、B 站、CSDN、简书、掘金、搜狐号、今日头条、微信公众号、微博、小红书、知乎。其中当时仍待 Java 迁移的 8 个是百家号、B 站、简书、掘金、搜狐号、今日头条、微博、小红书；**简书及百家号、掘金、今日头条、微博已于本次完成代码迁移（预发布）**，实际联调待验证。剩余 3 个（B 站、搜狐号、小红书）仍待迁移。**登录状态可能变化，本次没有重新查询数据库，接手必须复核。**
- 简书有过独立 Playwright 草稿创建与回读实验，本次已落到 Java 策略，但浏览器编辑器选择器仍需已登录会话实测确认。其他平台曾遇到账号权限、风控、编辑器加载或保存失败等问题；具体障碍应重新复现，不把旧观察当成恒定结论。
- **2026-10-07 真实探索（只读）**：重新查询数据库，7 个待迁移平台全部 `LOGGED_IN`；用其 Playwright 登录态在真实 Chromium 中逐个打开创作后台，已定位各平台**真实的发文入口 URL 与编辑器选择器**，详见 [.doc/平台发文入口与选择器.md](平台发文入口与选择器.md)。关键结论：百家号 `data-testid=news-title-input`／`publish-btn`；掘金 `input.title-input`＋CodeMirror；今日头条 `div.ProseMirror`＋`预览并发布`；微博分「短微博 composer」与「头条文章 `card.weibo.com/article/v5/editor`」；B 站专栏编辑器在 iframe `york/read-editor`（需 LV1+）；**搜狐号账号未实名，仅能发动态、文章被实名门槛阻断**；**小红书无纯文本文章（图文须带图，另有「写长文」）**。探索阶段未执行任何真实存草稿／发布，未产生平台草稿。
- **2026-10-07 晚真实预发布联调（发布 #16/#17/#18）三条均未成功，已用真实登录态复现根因**（DB 实证：三条记录均为 20:57–20:59，耗时 ≈30s 超时＋启动；全表 `WEIBO`=0 条）。详见 [.doc/平台发文入口与选择器.md](平台发文入口与选择器.md) §4：
  - **百家号 #18**（status=5 预发布失败，reason「编辑器无法打开」）＝ **误报，代码 bug**。截图证明编辑器正常打开；正文在 UEditor 的 iframe 内，客户端 `BODY_SELECTOR` 取 `.nth(1)` 实际 count=0（主文档只有标题 1 个 contenteditable），30s 超时被错归为「编辑器打不开」，与登录态/网络无关。
  - **今日头条 #17**（status=1 预发布中/未知，reason「草稿结果未确认」）＝ **误报，代码 bug**。默认视口 1280×720 下编辑页自动弹出「头条创作助手」抽屉＋全屏遮罩 `div.byte-drawer-mask`，拦截正文 `click()`（`fill()` 不受影响故标题已填、卡在正文点击），30s 超时、无草稿产出。另侧栏提示「请完善账号信息」、编辑页会载入历史草稿。
  - **掘金 #16**（status=1，reason「草稿结果未确认」）＝ **账号阻碍＋代码 bug**。`article_draft/create` 返回 `403 must bind phone`（账号未绑手机号）；且客户端用「URL 跳转 `/editor/drafts/{id}`」判成功，但掘金编辑页 URL 不跳转，判据本身必然超时。
  - **代码修复已于 2026-10-08 落到对应 `BrowserClient`（已离线编译全工程通过、9 个相关单测绿灯），待真实联调验收**：①`BaijiahaoBrowserClient` 正文改 `findUEditorFrame` 按 frame 定位 UEditor iframe 的 body（不再用主文档 `.nth(1)`）；②`ToutiaoBrowserClient` 新增 `dismissAssistantDrawer` 在导航后关闭「头条创作助手」抽屉与 `byte-drawer-mask` 遮罩、正文 click 前清理遮挡；③`JuejinBrowserClient` 改用 `page.onResponse` 捕获 `article_draft/create` 响应、按 `err_no` 判定成功，并把 `403 must bind phone` 明确映射为 `PublicationClientException`（确定失败，status=5），不再依赖永不跳转的 URL。④`draftMayExist` 语义未变，但选择器/遮罩修正后正常分支不再误报。
  - 账号侧：掘金须先绑手机，否则代码改完也无法真正建草稿；头条侧栏提示完善账号信息；已清理头条联调残留草稿。
  - **微博在本次批次未产生任何发布记录（全表 `WEIBO`=0 条）——已确认非代码 bug**：微博策略 `WeiboPublicationStrategy` 是正常 `@Component`（`platformType()=WEIBO`），`/publication/platforms` 会把微博列入可选平台；而 `ContentPublishBusiness.prePublish` 会为每个 `platformTypes` 中的平台先建初始化记录再跑策略。微博=0 说明那次批次的 `platformTypes` 根本没包含 WEIBO，属于批次选择问题，下次联调在平台勾选里加上微博即可（其客户端与策略均已就绪）。
- **2026-10-08 第二轮真实联调（#19–#26）与三处跟进修复（已编译+单测通过，待第三轮验收）**：知乎、**今日头条成功（遮罩修复生效）**；简书/百家号/掘金的失败经真实登录态复现均为"草稿已建成但判定错误"或"推断选择器不存在"，详见 [.doc/平台发文入口与选择器.md](平台发文入口与选择器.md) §4.7：
  - `BaijiahaoBrowserClient`：回读比较前归一化（UEditor 会在正文后追加含零宽字符 `\u200D` 的空段落，严格相等把已保存草稿误判为未回读）。
  - `JuejinBrowserClient`：成功响应字段实测为 `data.id`（不存在 `data.draft_id`），已加回退。
  - `JianshuBrowserClient`：按真实 DOM 重写——写作页 hash 路由且自动打开旧笔记（URL 已含 `notes/{id}`，须与新建 id 区分）、无 contenteditable/「请输入标题」、正文是 `textarea#arthur-editor`；已用真实会话完成端到端实测并清理测试笔记。
  - 小红书/B 站「未登录」为预期失败（**当时**）：仍未迁移、走 Wechatsync 通道（其 Chrome 登录态无这两个平台）；**小红书已于 2026-10-09 按自管模式重新接入（写长文建草稿），B 站仍待自实现**；微博连续两轮未纳入批次勾选。

### 未完成

- 上述仍有有效登录态的平台逐个迁移、单测和 Java 服务真实预发布验收；不能靠页面打开或 HTTP 200 认定草稿成功。
- 除知乎外的平台真实公开发布及其页面/应用层完整链路，尚不能宣称完成。
- 已无 Wechatsync 残留：源码、配置文档（`config/wechatsync.md` 已删）、部署 SOP 与 SQL 注释均已清理；`config/` 目录仅剩 `browser-login.md` 与 `zhihu-publish.md`。被移除的 19 个 Wechatsync 平台策略子类后续如需恢复，按已自管的 9 个平台模式（知乎、百家号、掘金、今日头条、微博、简书、微信公众号、CSDN、小红书）新建 `XxxBrowserClient` + 改 `extends AbstractPublicationStrategy`，并补真实联调（其中小红书已于 2026-10-09 按此模式恢复，剩余 B 站、搜狐号、抖音等 18 个仍待自实现）。

## 4. 关键决策与约束

- 应用层管理内容记录和状态；每平台策略负责选择能力，平台浏览器客户端负责页面差异；`PlaywrightBrowserComponent` 只提供浏览器基础能力，不收纳平台选择器。
- 当前一个平台一个账号；`platform_browser_login` 保存 Playwright `storage_state`。登录状态快照可能过期，执行前需真实访问校验。账号口令、验证码不作为发布记录保存。
- `content_publish_record.content_optimization_record_id` 关联优化记录 ID，不强制数据库外键；用户确认最终稿后再创建逐平台发布记录，保留确认稿快照。
- 发布状态按 `ContentPublishRecordStatus` 与现有表/接口定义执行。结果无法确认时不得伪报成功，也不得自动重试可能已提交的草稿或公开发布。
- 不把联调稿公开发布；第三方平台可能存在账号资质、滑块、短信或网络限制，不绕过风控。
- 不凭代码生成量判断完成。每个平台至少要验证会话恢复、草稿创建、平台返回 ID/链接或等价成功信号，以及重开同一草稿回读标题和正文。无法达到时写清阻碍与证据。
- 不自动升级依赖、改现有平台/状态枚举编码、表名或公共 `prePublish`/`publish` 契约；如确需变更，先评估页面、数据库和调用方兼容性。
- 仓库中的运行配置可能含敏感值。不要把配置值、Cookie、`storage_state`、后台 token 复制到文档、日志或答复中。

## 5. Git 与本轮改动

- 分支 `20261007_pre_publish`；最近提交依次为 `85b4003 commit`、`8541bf1 项目独立：迁移与拆分`、`54ee63d Initial commit`。提交标题不足以说明实现，以 `git show` 为准。
- **GPT 本轮（文档）**：新增根目录 `AGENTS.md`、更新 `.doc/HANDOFF.md` 为新仓库状态；未改业务代码、POM、数据库或运行配置，未提交。
- **WorkBuddy 本轮（代码，2026-10-07，未提交）**：完成简书预发布迁移。
  - 新增 `src/main/java/.../publish/browser/jianshu/JianshuBrowserClient.java`：恢复 DB 登录态、建草稿、重开回读；选择器标注“待真实联调”。
  - 改写 `src/main/java/.../publish/strategy/browser/jianshu/JianshuPublicationStrategy.java`：改 `extends AbstractPublicationStrategy`，注入 `JianshuBrowserClient`，弃用 Wechatsync 基类；`execPublish` 仍抛“暂未接入”。
  - 新增 `src/test/java/.../browser/jianshu/JianshuBrowserClientTest.java`、`.../strategy/browser/jianshu/JianshuPublicationStrategyTest.java`。
  - 修改 `src/test/java/.../browser/WechatsyncPrePublicationStrategyTest.java`：移除简书案例、断言计数 24→23（简书已不再继承 Wechatsync 基类）。
  - 验证：`mvn -o test -Dtest=JianshuBrowserClientTest,JianshuPublicationStrategyTest,WechatsyncPrePublicationStrategyTest` 全部通过；全量 `mvn -o test` 结果为 225/226 通过，唯一失败是 `WechatsyncClientTest.登录检查超时时不应同步正文`（测 `WechatsyncClient` 登录检查超时文案，与简书改动无关，属独立仓库未重跑测试的预存问题，待单独排查）。
  - 风险：简书编辑器选择器未实测，真实联调前不得宣称草稿成功；接手须复核数据库 `LOGGED_IN` 与简书写作页实际结构。
- **WorkBuddy 本轮（代码，2026-10-09 晚，未提交）：接入小红书自管草稿通道**。
  - 新增 `src/main/java/.../publish/browser/xiaohongshu/XiaohongshuBrowserClient.java`：恢复 DB 登录态（`XIAOHONGSHU` 须 `LOGGED_IN` + 可恢复 storage_state JSON）、进创作中心→发布笔记→写长文 tab→新的创作、填标题（`textarea[placeholder="输入标题"]`）与正文（`div.tiptap.ProseMirror`，聚焦后 `keyboard().insertText`）、**离开编辑器前页内回读标题与正文**、点「暂存离开」存草稿；长文草稿 id 无法可靠提取时 `remoteContentId` 返回 null（与微博一致）。
  - 新增 `src/main/java/.../publish/strategy/browser/xiaohongshu/XiaohongshuPublicationStrategy.java`：`extends AbstractPublicationStrategy`，`platformType()=XIAOHONGSHU`；`execPrePublish` 委托客户端，`execPublish` 暂抛"小红书草稿公开发布暂未接入"（长文"发布"按钮待真实联调确认）。`@Component` 自动接入 `PublicationPlatformStrategyRouter` 与 `listConfiguredPlatforms`，前端 `/publication/platforms` 自动露出、登录态列表自动纳入。
  - 新增 `src/test/java/.../strategy/browser/xiaohongshu/XiaohongshuPublicationStrategyTest.java`（断言返回 `DRAFT_CREATED` 并透传 `draftUrl`/`remoteContentId`）。
  - 验证：`mvn -o clean test` 全量 **216** 通过（原 215 + 新增 1）；`TestControllerPublicationTest` 覆盖 `platform=XIAOHONGSHU` 路由返回 `DRAFT_CREATED`。
  - 风险：写长文选择器（`button:has-text('发布笔记')`、`.creator-tab:has-text('写长文')`、`button.new-btn`、标题/正文、`暂存离开`）来自只读探索记录，未在本账号真实会话实测；长文草稿是否真正落库以小红书草稿箱核对为准，发布能力未实现。
- **WorkBuddy 本轮（代码，2026-10-09，未提交）：东方财富按自管模式接入并真实验证（草稿能力）**。
  - 新增 `EastmoneyPublicationStrategy`（`extends AbstractPublicationStrategy`，`platformType()=EASTMONEY`，`@Component`）与 `EastmoneyBrowserClient`；`execPublish` 暂抛"东方财富草稿公开发布暂未接入"。`@Component` 自动接入路由与 `listConfiguredPlatforms`，前端 `/publication/platforms` 与登录态列表自动露出东方财富。
  - **已真实验证**：用户在「浏览器登录管理」登录东方财富后，`platform_browser_login` 出现 `EASTMONEY=LOGGED_IN`（账号名 股友15R258878p，storage_state 5887 字符）。用 `geo-explore-harness`（真实 macOS headless Chromium + 真实 storage_state）探索并跑通完整链路：创作平台入口 `mp.eastmoney.com/collect/pc_article/index.html#/`、标题 `input[placeholder="标题(1-64字)"]`、正文 `div.ProseMirror.cfh_editor_area`（ProseMirror，聚焦后 `insertText`）、保存「保存并预览」后出现「草稿已保存」、页内回读 56 字正文；重载编辑器出现「您有一篇未编辑的文章」旧草稿提示、点「点击载入」可恢复原稿——即服务端已落库。生产 `createDraftInBrowser` 据此实现（含旧草稿提示处理：载入→清空→重写→保存→回读），`remoteContentId` 取 null（单活跃草稿模型，hash 路由无 id）。**非凭印象推断，已在真实浏览器跑通**。
  - 新增 `EastmoneyPublicationStrategyTest`（断言返回 `DRAFT_CREATED` 并透传 `draftUrl`/`remoteContentId`）。
  - 验证：`mvn -o clean test` 全量 **214** 通过（含路由唯一性与 `TestControllerPublicationTest` 的 `platform=EASTMONEY` 路由命中）；真实浏览器联调在沙箱外 macOS 环境跑通。
  - 待办：`execPublish` 在发布按钮真实确认前保持"暂未接入"；若需发布，先确认编辑器发布触发方式并从保存后状态捕获草稿 id 才能重开草稿。详见 `.doc/平台发文入口与选择器.md` §EASTMONEY。
  - **更正（2026-10-09 稍后）**：上面第 93 条的"已真实验证"仅证明 **Node 探索脚本**跑通；生产 `EastmoneyBrowserClient` 里有**两个 bug 从未被执行到**，导致 UI 上长期显示「预发布中／待核对 · 东方财富草稿结果未确认，请核对草稿箱，勿直接重试」：
    1. 保存按钮用 `button:has-text('保存并预览')`，真实元素是 `<div class="button_preview ...">` → 命中 0 个、`click()` 干等 30s 超时（与记录耗时 ~37s 吻合）；
    2. `page.waitForFunction(expr, WaitForFunctionOptions)` 在 Playwright Java 不存在该重载 → 抛 `Unsupported type of argument: Page$WaitForFunctionOptions`。
    两处都在 `draftMayExist=true` 之后抛 PlaywrightException，被包装为"结果未确认"→ `PRE_PUBLISHING(1)`。
  - **修复与复测**：改为 `page.getByText("保存并预览", new Page.GetByTextOptions().setExact(true))` 与 `page.waitForFunction(expr, null, new Page.WaitForFunctionOptions()...)`（"点击载入"同步改为 `getByText` 精确匹配）。用独立 runner 直接调用真实 `EastmoneyBrowserClient.createDraft`（DAO 用动态代理桩注入 DB 真实登录态），返回 `DRAFT_CREATED`、耗时 14.1s；重载编辑器 → 「您有一篇未编辑的文章」→ 点「点击载入」→ 标题逐字匹配、正文 97 字，确认服务端真落库。`mvn -o test` **214** 全绿。
  - **教训**：探索脚本验证通过 ≠ Java 客户端验证通过；两边选择器不同会让生产路径带病上线。后续平台接入必须用真实 Java 调用（或重启后的 HTTP 端点）复测。
  - **注意**：已存在的 `content_publish_record#30`（EASTMONEY, status=1, "东方财富草稿结果未确认…"）是本次 bug 造成的**误报**，并非真的结果不确定；重启应用后重跑预发布即可覆盖，或按 `.doc` 说明手工订正。
- 接手时重新查看 `git status` 与 diff，保留上述未提交改动；未提交前不要自动清理或批量格式化。
- 本次 `rg -n 'TODO|FIXME' src/main/java src/test/java` 未发现命中；这不等于功能已完成。

## 6. 下一步（按优先级）

1. 阅读 `AGENTS.md`、本文件、`.claude/CLAUDE.md` 和相关设计文档；检查 `git status`、最近 diff，核对登录表中的平台及状态，**不要输出 `storage_state`**。对照 24 个旧策略与已有平台客户端列出现时仍可迁移的平台。
2. 简书，及百家号、掘金、今日头条、微博预发布代码迁移已完成（浏览器客户端 + 策略 + 单测），小红书已按自管模式重新接入（写长文建草稿）；真实平台联调待已登录会话验证；历史草稿 ID `144599844` 不得硬编码。验证通过后即可视为该平台示例闭环，再推进其余 2 个（B 站、搜狐号）。
3. 7 个平台的真实编辑入口、标题／正文／保存／发布选择器已在 `.doc/平台发文入口与选择器.md` 记录（只读探索所得）。百家号、掘金、今日头条、微博的自管浏览器客户端与策略代码已完成、单测通过，小红书已接入写长文建草稿，下一步据此做真实联调验收（尤其百家号/今日头条/微博的草稿 URL 与 ID 提取，以及小红书真实账号下的写长文编辑器与"暂存离开"存草稿链路）；**B 站专栏需处理 iframe 且受 LV1+ 限制；搜狐号文章受实名门槛阻断**，按阻碍单列并保持未完成。按"代码完成/单测通过/真实联调通过"分别记录证据，不做空成功实现。
4. 实际公开发布与页面操作单独验收，必须使用明确获准公开的测试内容。Wechatsync 及过时文档已于 2026-10-09 清理，系统草稿能力由自管 Playwright 通道提供。

## 7. 验证与交接方式

- 运行 `mvn -q test`（JDK 17）；数据库启动和连接按 `docker/README.md` 与运行配置。**本次仅编辑文档，未重跑测试**。旧交接记录曾报告 2026-10-07 14:24 有 234 项测试通过，迁移后的当前结果待重新验证。
- 启动 `GeoBoot` 后查看 `/admin/browser-login.html`、`/admin/contents.html`、`/admin/publications.html`；用测试内容核对接口返回、`content_publish_record` 的状态与平台草稿箱实际内容。不要仅用单测证明第三方平台能力。
- 交给另一工具前更新本文件的时间、当前目标、已完成、未完成、实际改动文件、测试命令及结果、风险与第一步；只写已核实事实，未知项标“待确认”。同一目录只允许一个工具正在编辑，平行工作另开 branch/worktree。

## CSDN 预发布修复（Codex，2026-10-09）

- 本次只修改 `CsdnBrowserClient.java`、`CsdnBrowserClientTest.java` 和本交接段落；百家号、今日头条由 WorkBuddy 负责，未改动其文件，也未重启原有 8010 服务。
- 已用数据库 CSDN 登录态复现原 Java 客户端失败。原因：标题未明确确认时保存为“【无标题】”；正文 `fill()` 丢失 Markdown 换行结构；重开草稿后只等正文非空，会提前读到编辑器欢迎内容。逐行 Enter 还会自动续写列表前缀，不能使用。
- 修复：标题按 Enter 确认并等待展示值更新；全选清空正文后，通过编辑器原生 `paste` 事件传入完整纯文本 Markdown；读取 `saveArticle` 的业务 code 和真实 `data.id`；重新打开该草稿，等待标题与完整正文一致。读取高亮编辑器 `textContent`，避免 `innerText` 为段落额外添加空行。
- 平台明确拒绝保存（如 code=400、频率限制）返回具体原因；受理但缺少 ID、回读超时等仍按结果未知处理，不自动重试。
- 验证：JDK 17 下 `mvn -o -q test -Dtest=CsdnBrowserClientTest,CsdnPublicationStrategyTest` **7 项通过**；真实 Java 客户端创建并回读草稿 `167396363` 成功。
- 独立启动当前服务到 8012，调用 `POST /test/prePublish/CSDN` 返回 HTTP 200、`DRAFT_CREATED`、`remoteContentId=167396374`。输入包含空行、列表、Java 代码块，客户端重开后全文核验通过。验证期间只创建测试草稿，未公开发布；这些测试草稿保留在 CSDN 草稿箱。
- 本次未通过内容列表接口创建数据库发布记录，也未测试 CSDN 实际公开发布；公开发布仍未接入。临时 8012 验证服务已停止，原服务需由使用者重新加载本次代码后再调用。

## 今日头条 / 百家号 预发布接入（WorkBuddy，2026-10-09）

- 两平台**代码早已存在**（`ToutiaoBrowserClient`/`BaijiahaoBrowserClient` + 对应策略 + 单测），之前只停留在"修复+单测通过"，未用真实登录态跑通 Java 客户端路径。本次按纪律**真实跑通**：用独立 runner 直接调真实客户端（DAO 以动态代理桩注入 DB 真实登录态；`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1` + 干净 `PLAYWRIGHT_BROWSERS_PATH` 绕开坏锁），两平台均返回 `DRAFT_CREATED`：
  - 头条：真实账号「天总会晴朗何必悲伤于此时」，14.1s，`remoteContentId=null`（自动保存模型，URL 无 id）。
  - 百家号：真实账号「是瑞瀛呀」，12.6s，`draftUrl` 带 `article_id=...`，`remoteContentId` 正确提取。
- **顺带修了两个会影响全部浏览器的生产环境 bug（与具体平台无关）**：
  1. **代理污染**：`HTTP(S)_PROXY=127.0.0.1:57162`（本机 AI 工具本地代理）被 Chromium 继承，常规网页请求被路由到非通用代理，导致页面加载异常/`launch()` 挂死。修复：`PlaywrightBrowserComponent.openBrowser` 启动 Chromium 加 `--no-proxy-server`；`PlaywrightBrowserComponentTest` mock 同步改为匹配 `launch(LaunchOptions)` 重载。
  2. **百家号草稿 ID 提取**：`DRAFT_ID` 正则原写 `[?&]id=(\d+)`，但真实 URL 是 `&article_id=...`，匹配不到 → `remoteContentId` 永远 null。改为 `[?&]article_id=(\d+)` 后正常提取。
- **坏锁遗留（AI 工具无权限删除受保护目录）**：诊断时多次强杀 Java 进程把 `~/Library/Caches/ms-playwright/__dirlock` 弄成残留锁，导致任何 `Playwright.create()` 都失败（抛 `Failed to create driver`）。用户重启应用前需清理：删除该锁（`rm -rf ~/Library/Caches/ms-playwright/__dirlock`）或给应用加 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`。当前 8010 应用已停（探测返回 000）。
- 验证：`mvn -o test` **218 全绿 / BUILD SUCCESS**。改动均未 git 提交，分支 `20261007_pre_publish`。
- `execPublish` 对 TOUTIAO/BAIJIAHAO 仍"暂未接入"（公开发布按钮待真实联调确认后实现），本次只验证草稿建联调。

## Linux 安装脚本（Codex，2026-10-09）

- `Deploy/install.sh` 安装 Ubuntu 22.04/24.04 x86_64 的 OpenJDK 17、Google Chrome stable、Xvfb、Xauth 和字体。固定平台及软件选择，不修改 `.env`、数据库或业务代码。
- `Deploy/README.md` 提供首次安装及 `xvfb-run` 启动示例。脚本通过 `bash -n`，本机 macOS 执行会在安装前拒绝；尚未在 Linux 实际安装或验证头条。Chrome + CDP 生产模式仍待实现，不能将依赖安装误认为平台链路完成。

## 微博预发布修复（Codex，2026-10-10）

- 用户发布记录 44（优化记录 7、WEIBO）仍保持预发布中，未手工改状态或重投用户正文。真实复现：未点击左侧“写文章”时，正文被 `.wb-editor-spin` 遮罩阻挡，旧代码超时后只返回笼统“待核对”。
- `WeiboBrowserClient` 先监听 `/article/v5/aj/editor/draft/create` 成功响应（业务码 100000），使用 `data.id` 等待本次新草稿路由，不能取草稿箱自动打开的旧 ID。正文可点击后再填标题，避免异步加载覆盖标题；正文走原生粘贴事务。
- 保存监听 `/article/v5/aj/editor/draft/save`，要求 HTTP 200 且业务码 100000；重载同一 ID 后核验标题和正文，仅忽略展示空行，不忽略文字、顺序、非空行边界或行内空格。不使用固定等待或页内输入充当成功证据。
- 真实项目 Java 客户端（数据库实际登录态、公共浏览器组件）创建草稿 **4082556** 成功；关闭后再用独立浏览器恢复同一数据库会话重开，完整正文核验通过。测试仅创建草稿，没有公开发布，也没有修改用户发布记录。
- `WeiboBrowserClientTest` 新增创建响应标识、保存成功码、富文本比较及“先写文章、正文可操作后填标题、保存后重载”顺序回归测试。运行微博客户端与策略定向测试；运行中的服务需重启加载改动。
- 排障期间留下专用测试草稿 4082549、4082551、4082552、4082553、4082555、4082556，均未公开。4082549 只用于诊断，曾被早期时序探针追加测试正文；其余部分为空稿。没有操作非测试草稿，后续清理仅能针对这些明确测试 ID。

## 微信公众号回读误判修复（Codex，2026-10-10）

- 发布记录 47（优化记录 7、WECHAT_OFFICIAL_ACCOUNT）显示预发布中。恢复数据库实际会话进入草稿箱，找到平台草稿 **100000006**：标题相同，正文输入 15 行、平台回读 22 行；仅忽略空行后全文一致。已证明此稿实际保存，失败原因是富文本增加展示空行导致严格字符串比较误判，不是登录态失效。
- `WeixinBrowserClient.verifySavedDraft` 强制重载同一草稿，再比较标题与规范化正文。只忽略空行及 CRLF/LF 差异，仍保留文字、顺序、非空行边界和行内空格；后台 token 不返回、不记录日志。
- `WeixinBrowserClientTest` 新增展示空行、丢字、顺序、非空行边界、行内空格及实际回读结果判定测试。公众号客户端与策略定向测试通过。
- 修复后的真实 Java 客户端（实际数据库会话、原公共浏览器组件）成功创建并重开核验专用测试稿 **100000007**，未公开。原草稿 100000006 和用户发布记录 47 没有修改或重投；运行服务需重启加载修复。原稿已存在，不能因数据库仍待核对而再次创建重复稿；如需修复历史状态，应按该草稿标识及完整内容核验后另行处理。

### 公众号草稿入口（2026-10-10）

- 内容详情、发布列表及发布详情通过共用 `draftEntry` 展示“打开公众号草稿箱”（有平台草稿 ID 时）。实际目标为 `https://mp.weixin.qq.com/`，用户在自己的浏览器登录后台后进入草稿箱按标题查找，不宣称是具体草稿深链；不携带 token，不改数据库 `draft_url`。其他平台原链接逻辑保持不变。
- 4 项行为断言及 3 个前端脚本语法检查通过。针对用户反馈的 `draftEntry is not defined`，内容页和发布页的共用脚本、调用脚本统一增加 `v=20261010-1`，避免旧缓存混用。同步 Maven 构建资源后，以真实 8010 发布列表验证脚本加载及公众号入口；未执行全部管理页面回归。

### 公众号直接查看指定草稿（2026-10-10，替代上述后台首页入口）

- 两页统一显示“打开草稿”；公众号改用只读弹窗。`POST /content-publish/detail/{id}/draft-preview` 按发布记录草稿 ID 恢复数据库会话，动态取得 token，打开指定平台编辑页，返回当前标题、正文和截图。不返回 Cookie/token/编辑地址，不更新发布状态，不创建或发表草稿；读取结束自动关闭浏览器。
- 新增 `ContentDraftPreviewService`、`ContentDraftPreviewController`、`ContentDraftPreview` 和共用 `draft-preview.js`。窗口支持刷新与关闭，不支持编辑或发表。两页脚本版本统一为 `v=20261010-3`；其他平台原链接逻辑不变。
- 15 项定向测试通过。独立 8012 服务禁用 SQL 初始化，以真实发布记录 48 / 草稿 100000008 验证接口 HTTP 200、禁止缓存、PNG 截图及平台正文 528 字符。真实发布列表点击后弹窗、图片、正文与关闭通过，无脚本错误。测试服务随后停止，用户原 8010 服务未停止；新增 Java 接口需重启原服务加载。

### 效果回收导航归属（2026-10-10）

- 六个管理页面统一将“效果回收”移入“内容投放”，位于“发布记录”之后；效果回收页面面包屑同步改为“内容投放 / 效果回收”。
- 真实 Chrome 复现旧 HTML 缓存导致内容页仍显示原分组；服务返回页面与源码一致，加版本参数后显示正确。六页导航与首页链接统一增加 `v=20261010-nav1`，同步 `target/classes` 静态页面至运行服务。
- 已在真实 8010 服务逐页点击六个导航入口，核验分组、顺序、当前页选中状态及效果回收面包屑通过，并检查页面截图。效果采集仍为待接入占位能力；已经打开的旧页面需刷新或通过带版本链接进入。

## 项目基础档案（Codex，2026-10-10）

- 当前范围已完成：一级“项目管理”及“项目创建／项目列表”，创建基础档案、按名称分页查询、查看当前页完整资料。尚未增加编辑、删除或监测／分析任务；后续任务可引用项目 ID。
- 分层为 ProjectController → ProjectBusiness → ProjectService → ProjectDao / ProjectEntity；Project 为不含 ORM 注解的领域模型，创建输入单独建模，不能覆盖系统 ID 和时间。遵循现有 MyBatis-Plus 持久化模式，未引入新依赖。
- geo_project 保存五项必填（名称、资料 URL、行业、核心功能、目标用户）与图中七项可选配置；别名、竞品、关键词为 JSON 列表，ID 自增，时间由数据库生成。URL 只保存且校验 HTTP(S)，不抓取页面。001 空库脚本和 006 增量脚本均包含建表定义，docker/README.md 已说明迁移。
- 本地数据库已执行 006，仅添加项目表。15 项定向测试通过，离线编译、JS 语法和差异检查通过。独立 8012 服务禁用 SQL 初始化，真实创建返回 201、ID 和时间；名称查询、JSON 列回读、分页排序及输入 400 验证通过。Chrome 实际创建后跳列表、详情、名称查询、无匹配／无项目状态通过；八页导航实际点击和高亮通过，截图核验后补齐 contents.css 共用按钮与分页样式。
- 两条明确 QA 项目（ID 1、2）已按 ID 与测试名称精确清理，项目表当前空；未操作已有内容、发布记录或平台草稿。原 8010 服务未停止，新增 Java 接口须重启原服务加载；独立测试服务完成后停止。导航版本统一为 v=20261010-project1，避免旧 HTML 导航缓存。
- Java 注释检查：新增类均说明职责与边界；创建、资料校验和应用编排按业务阶段添加注释；查询、转换、字段规则、URL 校验及错误映射有方法说明。后续任务关联时再按任务设计新增 project_id，不把执行结果放入基础档案。

## WorkBuddy / GPT 接手指令

### 今日头条保存判据修正（Codex，2026-10-09 晚间）

- 用户记录 `content_publish_record.id=39`（优化记录 7、TOUTIAO）出现正文回读不一致。真实复现确认 ProseMirror 的 `innerText` 会额外插入展示空行；原键盘 `insertText` 也会产生不稳定的空行结构。
- `ToutiaoBrowserClient` 改为原生纯文本粘贴事务；比较保留非空文本行边界，不忽略文字、顺序或行内空格。保存不再固定等待 2 秒：监听 `/mp/agw/article/publish` 响应，必须业务 `code=0` 且有效字符串 `data.pgc_id`，再重开 `graphic/publish?pgc_id=...` 核验。当前生产客户端已拒绝将 `7050` 或 `pgc_id=0` 报为成功。
- 真实数据库会话 + 项目 Java 浏览器组件复测：平台 HTTP 200，但业务返回 **7050（保存失败）**，因此结果仍待核对，**本平台未修复到端到端成功**。保存失败原因待继续定位（账号/会话/请求字段/平台风控均未确认），不要猜测或绕过。
- 草稿箱只读查询未发现与优化记录 7 本次标题一致的草稿；未修改记录 39 或任何用户草稿。临时草稿联调未公开发布，平台未返回有效草稿标识。
- 定向命令 `mvn -o -q test -Dtest=ToutiaoBrowserClientTest,ToutiaoPublicationStrategyTest` 通过。新增测试覆盖展示空行、文本边界、保存失败码及长字符串标识。
- 上文 WorkBuddy 记录的头条 `DRAFT_CREATED/remoteContentId=null` 仅能证明旧页内检查通过，不能证明平台落库；后续验收以保存成功响应和重开回读为准。原服务未重启，需要重新加载代码。
- 后续决定性对照：使用普通 Chrome 的实际会话，通过浏览器桥接写入测试稿，平台返回 `code=0 / 保存成功`，草稿标识 `7694677939891421706`，正文回读一致；测试稿未公开，暂保留。证明平台支持草稿且该账号具备保存能力。
- 隔离 Playwright 使用相同标题、正文 HTML 和相同输入方式仍返回 7050；有界面模式、从主页进入、延长初始化等待、清除本地缓存、临时复制普通 Chrome 的完整 Cookie/localStorage/sessionStorage 均未解决。复制只在内存诊断上下文进行，未更新数据库。不能据此宣称“Cookie 过期”或明确某一风控规则；已定位至浏览器运行/会话环境差异，平台内部拒绝原因未知。
- 下一项关键验证需要本人在系统内重新扫码登录头条：验证新建 Playwright 登录会话立即创建草稿是否成功，从而区分历史数据库会话问题与新浏览器环境限制。扫码/身份验证不可代替用户完成。不要直接导入普通 Chrome 会话到生产表或迁移为浏览器扩展通道；若证实必须保持浏览器会话连续性，先确认持久化浏览器方案与服务器部署影响。
- 23:06 用户重新登录后继续验证：数据库 `last_login_at=2026-10-09 23:06:22.323`、LOGGED_IN；真实 Java 默认浏览器仍返回 7050。不能继续把问题解释为 session 过期。
- 使用**同一份新数据库会话**，独立有界面 Chrome 普通进程启动、显式非零 CDP 端口、默认持久上下文，再由 Playwright 连接控制，保存成功两次：`7694685214634672681`、`7694686089839411766`。后者重新启动独立浏览器再打开同 ID，标题及完整测试正文核验均通过；未公开发布。
- 对照：普通 Chrome 上述启动加 `--headless=new` 后返回 7050；Playwright 直接启动（包括有界面、去除默认参数及持久上下文）仍返回 7050。已证明当前可工作的路径是“普通有界面 Chrome + CDP + 默认持久上下文”，尚不能声明某个单独启动参数或平台内部风控规则是根因。生产方案尚未替换：计划只为头条增加此浏览器模式，通过公共组件提供，仍使用数据库会话，其他平台不变。需要确认服务器有界面浏览器/虚拟显示依赖，不应偷偷全局关闭 headless 或引入硬编码本机 Chrome 路径。

> 先阅读 `AGENTS.md` 与 `.doc/HANDOFF.md`，随后阅读 `.claude/CLAUDE.md` 和适用规则；检查当前 Git 状态、差异和相关代码。不要立即大规模重构，也不要把过时的登录态快照当成当前事实。先用简短中文确认当前目标、已完成/未完成范围、风险和首个验证动作，再从剩余的平台迁移任务继续开发。每完成一个平台，分别记录代码、单测和真实联调结果；不要自动提交或删除仍有引用的 Wechatsync。
