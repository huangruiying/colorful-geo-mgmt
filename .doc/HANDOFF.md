# Project Handoff

> 核对日期：2026-10-07，仓库 `/Users/huangry/Documents/huangry-code/colorful-geo-mgmt`。本文件是协作快照，不是聊天记录或平台联调成功证明。接手者先复核 Git、代码与数据库状态。共同规则见仓库根目录 [AGENTS.md](../AGENTS.md)。

## 1. 项目概述与当前目标

本项目是独立的 GEO 内容管理服务：录入内容、调用大模型优化、保存优化记录、确认最终稿、逐平台预发布草稿，并记录状态与平台返回值。`prePublish` 是创建草稿；`publish` 是将已有草稿公开发布，不能混为一谈。

**当前开发主线**：把仍通过 Wechatsync CLI 预发布的平台逐步迁移到自有 Playwright 客户端，并使用数据库保存的浏览器登录会话。用户要求优先完成已有登录态的平台，不能只改一两个便宣称“全改”。每个平台应取得真实可核验的草稿结果；没有有效登录态、账号权限或平台成功信号时，明确保留未完成状态。

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
- 迁移后项目的根 `pom.xml` 已使用 Spring Boot starter parent、独立 artifactId 和 Spring Boot Maven 插件；本次不改 POM 或业务代码。

### 进行中 / 待验证

- 当前代码中仍有 **24 个**具体策略继承 `AbstractWechatsyncPrePublicationStrategy`。这是本次仓库搜索结果，不等于 24 个平台都有有效登录态或可以发文。
- 2026-10-07 14:24 的历史数据库快照曾显示 11 个 `LOGGED_IN` 平台：百家号、B 站、CSDN、简书、掘金、搜狐号、今日头条、微信公众号、微博、小红书、知乎。其中当时仍待 Java 迁移的 8 个是百家号、B 站、简书、掘金、搜狐号、今日头条、微博、小红书。**登录状态可能变化，本次没有重新查询数据库，接手必须复核。**
- 简书有过独立 Playwright 草稿创建与回读实验，但 Java 策略仍走 Wechatsync。其他平台曾遇到账号权限、风控、编辑器加载或保存失败等问题；具体障碍应重新复现，不把旧观察当成恒定结论。

### 未完成

- 上述仍有有效登录态的平台逐个迁移、单测和 Java 服务真实预发布验收；不能靠页面打开或 HTTP 200 认定草稿成功。
- 除知乎外的平台真实公开发布及其页面/应用层完整链路，尚不能宣称完成。
- Wechatsync 旧代码/配置/部署依赖尚不能删除：生产策略仍有引用。须在全部目标平台完成替代、确认无生产引用后再清理。

## 4. 关键决策与约束

- 应用层管理内容记录和状态；每平台策略负责选择能力，平台浏览器客户端负责页面差异；`PlaywrightBrowserComponent` 只提供浏览器基础能力，不收纳平台选择器。
- 当前一个平台一个账号；`platform_browser_login` 保存 Playwright `storage_state`。登录状态快照可能过期，执行前需真实访问校验。账号口令、验证码不作为发布记录保存。
- `content_publish_record.content_optimization_record_id` 关联优化记录 ID，不强制数据库外键；用户确认最终稿后再创建逐平台发布记录，保留确认稿快照。
- 发布状态按 `ContentPublishRecordStatus` 与现有表/接口定义执行。结果无法确认时不得伪报成功，也不得自动重试可能已提交的草稿或公开发布。
- 不把联调稿公开发布；第三方平台可能存在账号资质、滑块、短信或网络限制，不绕过风控。
- 不凭代码生成量判断完成。每个平台至少要验证会话恢复、草稿创建、平台返回 ID/链接或等价成功信号，以及重开同一草稿回读标题和正文。无法达到时写清阻碍与证据。
- 不自动升级依赖、改现有平台/状态枚举编码、表名或公共 `prePublish`/`publish` 契约；如确需变更，先评估页面、数据库和调用方兼容性。
- 仓库中的运行配置可能含敏感值。不要把配置值、Cookie、`storage_state`、后台 token 复制到文档、日志或答复中。

## 5. Git 与本轮文档修改

- 2026-10-07 本次编辑前：分支 `20261007_pre_publish`，`git status --short` 为空；最近提交依次为 `85b4003 commit`、`8541bf1 项目独立：迁移与拆分`、`54ee63d Initial commit`。提交标题不足以说明实现，以 `git show` 为准。
- 本轮仅新增根目录 `AGENTS.md`、更新 `.doc/HANDOFF.md`，供 GPT/Codex 与 WorkBuddy 顺序交接；未修改业务代码、POM、数据库或运行配置，未提交。接手时重新查看 `git status` 与 diff。
- 本次 `rg -n 'TODO|FIXME' src/main/java src/test/java` 未发现命中；这不等于功能已完成。

## 6. 下一步（按优先级）

1. 阅读 `AGENTS.md`、本文件、`.claude/CLAUDE.md` 和相关设计文档；检查 `git status`、最近 diff，核对登录表中的平台及状态，**不要输出 `storage_state`**。对照 24 个旧策略与已有平台客户端列出现时仍可迁移的平台。
2. 优先将已有独立实验基础的简书流程落到 `infrastructure/client/publish/browser/jianshu/` 与 `strategy/browser/jianshu/`；补单测，并用新测试草稿通过 Java 服务创建、重开回读。历史草稿 ID 不得硬编码。
3. 对其他仍有有效登录态的平台逐个定位真实编辑入口、权限与成功信号，补对应浏览器客户端和策略；按“代码完成/单测通过/真实联调通过”分别记录证据。无法真实确认的平台保持未完成，不做空成功实现。
4. 实际公开发布与页面操作单独验收，必须使用明确获准公开的测试内容；最后在生产引用归零后清理 Wechatsync 及过时文档。

## 7. 验证与交接方式

- 运行 `mvn -q test`（JDK 17）；数据库启动和连接按 `docker/README.md` 与运行配置。**本次仅编辑文档，未重跑测试**。旧交接记录曾报告 2026-10-07 14:24 有 234 项测试通过，迁移后的当前结果待重新验证。
- 启动 `GeoBoot` 后查看 `/admin/browser-login.html`、`/admin/contents.html`、`/admin/publications.html`；用测试内容核对接口返回、`content_publish_record` 的状态与平台草稿箱实际内容。不要仅用单测证明第三方平台能力。
- 交给另一工具前更新本文件的时间、当前目标、已完成、未完成、实际改动文件、测试命令及结果、风险与第一步；只写已核实事实，未知项标“待确认”。同一目录只允许一个工具正在编辑，平行工作另开 branch/worktree。

## WorkBuddy / GPT 接手指令

> 先阅读 `AGENTS.md` 与 `.doc/HANDOFF.md`，随后阅读 `.claude/CLAUDE.md` 和适用规则；检查当前 Git 状态、差异和相关代码。不要立即大规模重构，也不要把过时的登录态快照当成当前事实。先用简短中文确认当前目标、已完成/未完成范围、风险和首个验证动作，再从剩余的平台迁移任务继续开发。每完成一个平台，分别记录代码、单测和真实联调结果；不要自动提交或删除仍有引用的 Wechatsync。
