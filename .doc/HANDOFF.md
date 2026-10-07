# Project Handoff

> 核对时间：2026-10-07 14:24（Asia/Shanghai）。本文件描述 `colorful-geo-management` 模块在该时点的代码和本地环境；平台页面、登录态、Git 工作区可能继续变化，接手时必须复核。**不要把本文件当成平台发布成功的证明。** 根目录 `HANDOFF.md` 是本文件的入口。

## 1. 项目概述

- 本模块是 GEO（生成式引擎优化）内容管理服务：录入原文、调用大模型优化、保存优化记录、由用户确认最终稿，再按平台创建草稿并记录结果。
- 当前主线是将平台投放从 Wechatsync CLI 逐步迁移到服务自管的 Playwright 浏览器能力，复用数据库中保存的登录会话。`prePublish` 创建草稿，`publish` 仅针对已有草稿执行公开发布，二者不能混用。
- **当前明确目标**：对数据库里已有 `LOGGED_IN` 记录、且策略仍依赖 Wechatsync 的平台，逐一改造、真实访问并验证；没有登录态的平台暂不改。上轮只完成 CSDN 和微信公众号两个新增迁移，用户要求继续完成其余已登录平台。不要把“已登录”直接等同于“草稿可保存”。

## 2. 技术栈

| 项目 | 当前事实 |
|---|---|
| 语言与框架 | Java 17；父 POM 管理 Spring Boot 3.2.0；模块入口 `org.huangry.colorful.geo.GeoBoot`，默认端口 8010。部署文档指定 Microsoft OpenJDK 17.0.17 LTS。 |
| 构建 | Maven 多模块；`colorful-geo-management/pom.xml` 使用 Maven Compiler、Surefire；**未显式配置** Spring Boot 可执行 JAR `repackage`，不能假设普通 JAR 能直接 `java -jar`。 |
| 数据库 | MySQL 8.4.11（本地 `docker/compose.yml`），MyBatis-Plus 3.5.17、MySQL Connector/J；DDL 和迁移 SQL 位于 `src/main/resources/db/mysql/`。 |
| 浏览器 | Playwright Java 1.58.0；`PlaywrightBrowserComponent` 管理隔离 Chromium 会话，数据库 `platform_browser_login.storage_state` 存 Playwright JSON。 |
| 大模型与其他依赖 | LangChain4j 1.10.0、OpenAI-compatible 通道、Fastjson、Lombok；POM 还包含 WhatsAppWeb4j 3.5.1。不要因为依赖存在就推断相关功能已接入当前投放链路。 |
| 页面与部署 | Spring Boot 同端口静态管理页在 `src/main/resources/static/admin/`；部署前提见 `src/main/resources/生产部署SOP.md`、`src/main/resources/config/` 和 `docker/README.md`。 |

## 3. 项目结构

- `application/content/`：内容优化、发布记录创建与状态回写的业务编排。
- `domain/service/optimization/`：GEO 内容优化；`domain/service/publish/`：逐平台路由与调用；`domain/service/content/`：记录服务。
- `infrastructure/client/llm/`：单个启用大模型提供方及可选联网引用；`infrastructure/client/login/`：平台浏览器登录流程。
- `infrastructure/client/playwright/`：浏览器资源与线程内任务基础组件；`infrastructure/client/publish/browser/`：平台编辑器/草稿/发布客户端；`infrastructure/client/publish/strategy/browser/`：每平台发布策略。
- `infrastructure/client/wechatsync/`：旧 CLI 通道，当前仍被 24 个策略使用，**不能现在删除**。
- `infrastructure/repository/` 与 `entity/`：MyBatis-Plus DAO、表实体；`infrastructure/common/enums/` 与 `exceptions/`：状态和异常。
- `presentation/controller/`：内容优化、内容发布、平台登录及测试接口；`static/admin/`：内容、发布记录、浏览器登录页面。
- `.doc/`：架构与平台接入说明；项目规则入口是仓库根目录 `AGENTS.md` → `.claude/CLAUDE.md`。根目录 README 是整个多模块仓库的通用介绍，不是本模块的最新接口契约。

## 4. 当前任务

以现有 `ZhihuPublicationStrategy` / `ZhihuBrowserClient` 的“策略 + 平台浏览器客户端 + 数据库会话 + 真实结果核验”分层为参照，迁移**当前已有数据库登录态**的平台。成功条件是：

1. 策略不再调用 Wechatsync；从 `platform_browser_login` 恢复该平台会话。
2. 真实创建草稿并重新打开核对标题、正文、平台 ID/链接；无法确认时不得返回 `DRAFT_CREATED`，也不得自动重试。
3. 已有草稿公开发布的 `publish` 与预发布分开实现；未经真实核验的平台不能伪报 `PUBLISHED`，更不能用标记“请勿公开发布”的联调稿测试公开发布。
4. 已登录范围迁移且验证完成后，再清理**确已无引用**的 Wechatsync 代码、配置和文档；未登录平台暂留旧策略，故当前不能全局删除旧通道。

## 5. 当前进度

### 已完成

- 知乎：策略已使用数据库 Playwright 会话，`ZhihuBrowserClient` 同时实现草稿创建和已有草稿公开发布（代码现状；本轮没有用真实公开发表再次验证）。
- CSDN、微信公众号：**预发布**客户端及策略改造已写入暂存区。独立 Playwright 浏览器实验使用数据库会话真实创建草稿并回读确认；Java 实现已通过单元测试，**尚未通过运行中的 Java 服务完成端到端真实调用**。CSDN 返回平台 ID 与编辑链接；公众号后台编辑链接含会话 token，只返回 ID，不持久化该链接。
- 当前测试：`mvn -q test` 于 2026-10-07 14:24 完成，36 个测试套件、234 个测试，0 失败、0 错误、0 跳过。测试通过不替代各平台真实联调。
- 简书：一次独立 Playwright 实验已创建并重新打开草稿，标题/正文回读一致，草稿 ID `144599844`；**Java 策略仍是 Wechatsync，尚未迁移**。

### 进行中

- 数据库在核对时有 11 条 `LOGGED_IN`：`BAIJIAHAO`、`BILIBILI`、`CSDN`、`JIANSHU`、`JUEJIN`、`SOHU`、`TOUTIAO`、`WECHAT_OFFICIAL_ACCOUNT`、`WEIBO`、`XIAOHONGSHU`、`ZHIHU`。其中后 8 个待迁移的已登录策略为：百家号、B 站、简书、掘金、搜狐号、今日头条、微博、小红书。接手时重新查表，不依赖这份快照。
- 已登录不保证平台业务权限：掘金实际保存请求返回 `must bind phone`；搜狐号页面提示账号未实名，只允许动态而非文章；百家号进入滑块验证；微博编辑器加载遮罩阻挡保存；头条保存请求返回“保存失败”；B 站专栏页没有加载出可操作编辑字段；小红书目前只确认进入视频上传页，文章/图文入口待核实。这些是联调观察，不是已经接入的实现。

### 未开始或未完成

- 上述 8 个已登录平台的 Java 迁移与逐平台完整验收；除知乎以外的已迁移策略中，CSDN 和公众号的 `execPublish` 仍明确抛未接入异常。
- 没有数据库可用登录态的剩余旧策略暂不迁移；全局移除 Wechatsync 的前置条件未满足。
- 管理页没有针对 `content_publish_record` 的实际公开发布按钮/控制器链路；当前页面主要提供确认最终稿、预发布、记录列表与删除。

## 6. 本轮修改

这里的“本轮”指**当前未提交改动**，不要把此前已提交文件误报为未提交。本次交接请求只新增文档，不改业务代码。

| Git 状态 | 文件 | 目的/注意 |
|---|---|---|
| 暂存新增 | `src/main/java/org/huangry/colorful/geo/infrastructure/client/publish/browser/csdn/CsdnBrowserClient.java` | 恢复 CSDN 数据库会话，创建、重开并核验 Markdown 草稿。 |
| 暂存新增 | `src/main/java/org/huangry/colorful/geo/infrastructure/client/publish/browser/weixin/WeixinBrowserClient.java` | 恢复公众号会话、创建与回读草稿；不向上返回含 token 的链接。 |
| 暂存修改 | `src/main/java/org/huangry/colorful/geo/infrastructure/client/publish/strategy/browser/csdn/CsdnPublicationStrategy.java` | CSDN 预发布切到自有客户端；公开发布仍未接入。 |
| 暂存修改 | `src/main/java/org/huangry/colorful/geo/infrastructure/client/publish/strategy/browser/weixin/WeixinPublicationStrategy.java` | 公众号预发布切到自有客户端；公开发布仍未接入。 |
| 暂存新增 | `.playwright-cli/page-2026-10-07T05-48-51-153Z.yml`、`.playwright-cli/page-2026-10-07T05-50-42-039Z.yml` | 浏览器探索快照，**不是运行时代码**；接手先确认是否保留，勿随手提交。 |
| 已提交（`710a401`） | CSDN/公众号客户端与策略测试、`BrowserTaskRunnerTest`、旧策略测试平台清单调整 | 单元测试已入 HEAD，不属于当前未提交差异；接手须检查实现与测试是否同步。 |
| 本次新文档 | `.doc/HANDOFF.md`、根目录 `HANDOFF.md` 入口 | 供 WorkBuddy 快速接手；本次无业务代码删除。 |

写入本交接文档前，模块没有未暂存代码修改；写入后新增两份 `HANDOFF.md`，且 `git status` 另显示若干未跟踪 `.DS_Store`。这些文件可能由本机或其他任务产生，不要代用户清理、提交或重置。

## 7. 关键技术决策

- **分层边界**：应用层编排记录和状态；策略按 `PublicationPlatformType` 选择平台；浏览器客户端负责页面动作；`PlaywrightBrowserComponent` 只管浏览器资源。不要把平台页面选择器放进通用组件。
- **单平台单账号**：`platform_browser_login.platform_type` 唯一，现阶段不引入租户、多账号、密钥管理。登录态 `storage_state` 存服务端数据库，手机号/密码/验证码不入库。
- **记录关系**：`content_publish_record.content_optimization_record_id` 关联优化记录 ID，不强制数据库外键；`(content_optimization_record_id, platform_type)` 唯一。用户确认稿后再生成逐平台记录，并保存标题/正文快照。
- **状态约定**：`ContentPublishRecordStatus` 对应数据库 `publish_status`：0 初始化、1 预发布中、2 预发布成功、3 实际发布中、4 实际发布成功、5 预发布失败、6 实际发布失败、7 取消。平台执行结果另见 `PublicationTaskStatus`。不要把“编辑器已打开”或“收到 HTTP 200”映射成成功。
- **结果不确定**：页面可能已提交、但响应或回读超时，须保留未知结果并提示人工核对；禁止自动重试造成重复草稿或重复公开发布。
- **被放弃的重要方案**：不再为已迁移平台通过 Wechatsync/Chrome 扩展建立第二套登录；不使用纯 Cookie 存在与否判定登录；不在没有真实平台反馈时以通用空实现声称全部支持。旧通道必须等实际引用消失后才删除。
- **接口现状**：内容优化 `POST /content-optimize/optimize`、`GET /content-optimize/list`、`GET /content-optimize/detail/{id}`；确认稿预发布 `POST /content-publish/{id}/pre-publish`；记录列表 `GET /content-publish/list`；删除 `DELETE /content-publish/detail/{id}`；浏览器登录基路径 `/admin/browser-login/platforms`。以当前 Controller 为准，`.doc/内容优化与多平台投放执行链路与架构.md` 中部分旧 URL/类名已过时。
- **凭据边界**：公众号编辑 URL 带后台 token，仅允许浏览器内使用；日志、异常、发布记录、交接文档都不得输出 token、Cookie 或完整 `storage_state`。

## 8. 当前代码状态

- Git 分支：`20261001_geo_mgmt`。最近与本模块有关的提交：`710a401`（测试）、`12a9b2f`（知乎说明修订）、`66e8842`（浏览器任务组件与配置）、`395aa98`（合并知乎草稿/发布客户端）、`a75e36b`（知乎使用数据库会话）。提交标题如 `test`/`commit` 信息量有限，以 `git show` 为准。
- 写文档前模块内 Git 暂存差异为 2 个 Java 新文件、2 个策略修改、2 个 Playwright 快照；写文档后新增两份未跟踪交接文档，另有若干未跟踪 `.DS_Store`。仓库**其他模块也有未提交/未跟踪内容**，不能在仓库根目录执行全量清理或批量提交。
- `src/main/java`、`src/test/java`、管理页 JS/HTML 中按 `TODO|FIXME` 搜索未发现命中；这**不代表功能已经完成**。旧 Wechatsync 基类仍被 24 个具体策略继承，相关源码/测试/配置存在多处引用。
- `.doc/知乎草稿接入.md` 和 `src/main/resources/config/browser-login.md` 保留了旧类名或旧能力描述；优先信任当前代码、数据库和可重复的真实测试。SQL 文件 `001_init.sql` 至 `005_simplify_platform_browser_login.sql` 的适用范围不同，勿重放迁移脚本或删除数据卷。

## 9. 已知问题

1. **本次任务未完成**：8 个当前已登录的旧策略尚未迁移；只有简书具备一次可回读的独立浏览器草稿实验，其余平台有权限、验证、页面加载或保存失败问题。应逐一定位，不要生成 8 个只返回成功的模板类。
2. CSDN/公众号仅完成纯文本/Markdown 简单正文的真实草稿测试；公众号富文本、图片、Markdown 格式保真和公开发布未完成验证。CSDN/公众号浏览器异常后可能已写入草稿，客户端按结果不确定处理。
3. 联调在第三方账号留下**未公开**测试草稿：CSDN `167220499`、微信公众号 `100000005`、简书 `144599844`。均使用“GEO 发布链路联调测试（请勿公开发布）”标题；不要把它们公开发布，不要未经核对批量删除。
4. 登录状态快照会过期；数据库 `LOGGED_IN` 只是已保存结论，发布前还需恢复会话并实际访问/必要时刷新验证。
5. 登录管理目前不做口令校验，数据库会话为明文 JSON；只应部署到受限环境。**已跟踪的** `src/main/resources/application.yml` 和 `docker/.env` 含敏感配置候选值；本次文档未复制值，接手应按安全流程核查、迁移至环境变量并轮换已暴露凭据。不要在日志、diff 或交接消息中打印原值。
6. 多平台真实公开发布可能产生不可逆外部状态。代码开发可继续，实测时只能使用明确获准公开的测试内容；测试稿标有“请勿公开发布”。

## 10. 下一步（按优先级）

1. **先复核边界与状态**：运行 `git status --short -- colorful-geo-management`、查询 `platform_browser_login` 的 `platform_type/login_status`（不查询/输出 `storage_state`）；打开 `AbstractPublicationStrategy`、`ZhihuBrowserClient`、`CsdnBrowserClient`、`WeixinBrowserClient` 和 8 个旧策略。确认没有并行修改后再编辑。
2. **先做简书迁移**：把已验证的 `https://www.jianshu.com/writer`“新建文章 → 写标题/正文 → 页面显示已保存 → 重开相同 note ID 回读”封装到 `infrastructure/client/publish/browser/jianshu/`，改 `JianshuPublicationStrategy`，补客户端/策略单测。先前实验 ID `144599844` 仅是验证样本，不要硬编码。重新用新测试草稿验证时记录 ID 并避免公开发布。
3. **其余 7 个已登录平台逐个平台定位并迁移**：`baijiahao/`、`bilibili/`、`juejin/`、`sohu/`、`toutiao/`、`weibo/`、`xiaohongshu/`。先解决上述账号权限或页面障碍、找到真实编辑入口和草稿成功信号；再新增各自 `publish/browser/<platform>/*BrowserClient`，替换对应 `strategy/browser/<platform>/*PublicationStrategy` 对 Wechatsync 的调用。每个平台必须重开同一草稿核验；不可确认则保持未接入/结果不确定。不要把已登录账号必然能发文当成前提。
4. **公开发布单独验收**：每平台先定义基于远程草稿 ID 的 `execPublish`、成功判据和失败/未知结果，再接应用层状态迁移及页面按钮。无获准公开的测试内容时只做单测/隔离验证，不能宣布真实发布已联调通过。
5. **最后清理旧通道**：先 `rg -n -i wechatsync src pom.xml` 确认零生产引用，再删旧客户端/基类/配置/部署步骤和对应测试；未登录平台仍依赖旧通道时不可删。清理前后跑全量测试，并核对平台列表与路由无缺失。
6. **单独处理安全与过时文档**：在不混入发布迁移的前提下，核查跟踪的敏感配置并轮换；更新 `.doc/知乎草稿接入.md`、`config/browser-login.md` 等与真实实现不符的描述。

## 11. 验证方式

- 本地数据库：在模块目录执行 `docker compose -f docker/compose.yml up -d`、`docker compose -f docker/compose.yml ps`；当前 `colorful-geo-mysql` 处于 healthy。空库建表见 `001_init.sql`；已有库按具体迁移 SQL 备份后升级，不要删除卷重建。
- Java：使用 JDK 17（本机已核对 Microsoft OpenJDK 17.0.17），配置 `GEO_MYSQL_URL`、`GEO_MYSQL_USER`、`GEO_MYSQL_PASSWORD` 等运行参数；从 IDE 运行 `GeoBoot`。当前 `http://127.0.0.1:8010/admin/index.html` 返回 HTTP 200；生产启动方式参阅 `生产部署SOP.md`，不要假设普通 JAR 可执行。
- 测试：在模块目录运行 `mvn -q test`；若终端未安装全局 Maven，本机可用 `/Users/huangry/.m2/wrapper/dists/apache-maven-3.9.9-bin/4nf9hui3q3djbarqar9g711ggc/apache-maven-3.9.9/bin/mvn -q test`，并设置本机 JDK 17 `JAVA_HOME`。本次核对结果为 234/234 通过。
- 页面与 API：`/admin/browser-login.html` 查看平台登录状态，`GET /admin/browser-login/platforms/login-statuses` 只读列表；`/admin/contents.html` 核对优化稿并创建草稿；`/admin/publications.html` 查看逐平台结果。真实预发布须用测试内容，检查数据库 `content_publish_record.publish_status`、`remote_content_id` 与平台草稿箱实际内容一致。
- 绝不在排查命令、测试报告或工单里打印 `storage_state`、手机号、密码、验证码、后台 token；仅输出平台名、状态、非敏感 ID 与结果分类。

## 12. 禁止事项 / 约束

- 不自动提交、重置或清理用户的 Git 改动；尤其不要将 `.playwright-cli` 快照和其他模块的脏文件一并提交。
- 不无证据升级 JDK、Spring Boot、MyBatis-Plus、LangChain4j 或 Playwright；当前迁移不需要依赖升级。
- 不随意修改现有 `PublicationPlatformStrategy` 的 `prePublish`/`publish` 契约、平台枚举、表名、`content_optimization_record_id`、唯一键或状态编码；若需改变，先核对应用层、页面和历史数据兼容性。
- 不把草稿当成公开发布，不伪造平台 ID，不因 HTTP 200/出现编辑器就写成功；对结果不确定的请求不自动重试。
- 不删除 Wechatsync，除非剩余实际引用已迁移或用户明确接受相关平台失去能力。
- 不公开发布联调草稿，不绕过验证码/风控，不输出或复制数据库会话和仓库中的密钥。
- 按根目录 `AGENTS.md` 与 `.claude/CLAUDE.md` 的路由、中文、注释、重构和测试规则开发；优先 Lombok 去掉样板代码，但不为使用注解牺牲语义。

## 13. WorkBuddy 接手提示

> 请先阅读本文件、仓库根目录 `AGENTS.md`、`.claude/CLAUDE.md`、相关 `.claude` 规则及本模块部署/设计文档。接着立即检查当前 `git status`、暂存和未暂存 diff、相关策略与数据库登录状态；本文件是 2026-10-07 的快照，不能代替实时检查。不要立即大规模重构或删除 Wechatsync。先用简短中文确认你理解的当前目标、已完成/未完成范围、已知风险和下一步验证方法，然后从**已登录但仍依赖 Wechatsync**的平台继续开发，优先简书；逐平台拿到可回读的真实草稿结果、补测试，再考虑公开发布和旧通道清理。
