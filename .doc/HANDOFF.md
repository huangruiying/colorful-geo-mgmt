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

## WorkBuddy / GPT 接手指令

> 先阅读 `AGENTS.md` 与 `.doc/HANDOFF.md`，随后阅读 `.claude/CLAUDE.md` 和适用规则；检查当前 Git 状态、差异和相关代码。不要立即大规模重构，也不要把过时的登录态快照当成当前事实。先用简短中文确认当前目标、已完成/未完成范围、风险和首个验证动作，再从剩余的平台迁移任务继续开发。每完成一个平台，分别记录代码、单测和真实联调结果；不要自动提交或删除仍有引用的 Wechatsync。
