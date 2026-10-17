# Project Handoff

> 核对日期：2026-10-14，仓库 `/Users/huangry/Documents/huangry-code/colorful-geo-mgmt`。本文件是协作快照，不是聊天记录或平台联调成功证明。接手者先复核 Git、代码与数据库状态。共同规则见仓库根目录 [AGENTS.md](../AGENTS.md)。

## 当前任务：诊断任务执行与模型 Bean 路由

- 2026-10-17 诊断报告接口改用 `diagnosisTaskId`（用户确认，避免以后与导出等其他任务混淆）：生成报告改为 `POST /diagnosis-reports/diagnosis-tasks/{diagnosisTaskId}`，删除 `CreateDiagnosisReportRequest`；列表筛选 `?diagnosisTaskId=`；`DiagnosisReportResponse`、`ResultResponse` 的 `taskId` 字段改为 `diagnosisTaskId`，注释写明对应 `geo_diagnosis_task.id` 等表字段；前端 `diagnosis-reports.js`、`diagnosis-tasks.js` 同步（版本 20261017-diagnosis-task-id）。内部变量、实体字段 `taskId` 与数据库列 `task_id` 不变。测试 367 项 366 通过（原有路由测试）。
  023 后真实写入验证（用户已重启 8010 为新代码）：方案 23 新建诊断任务 37（requestId `qa-rename-023-20261017043315`），5 条千问采样全部成功；生成报告 6（改名前接口创建，重启后新代码续跑完成），5 条能链 claude 判定全部成功，summary 7 个部分写入；库内 task/task_result/task_report/task_result_analysis 四表行与关联均正确，新列表筛选 `diagnosisTaskId=37` 返回报告 6。测试数据保留未清理。新生成接口仅单测覆盖，未用真实请求调用。未提交。

- 2026-10-17 按用户确认诊断四张表按层级统一改名（新增 `023_rename_diagnosis_task_tables.sql`，RENAME 保留数据，索引与约束同步改名，`001_init.sql` 改为新定义）：`geo_diagnosis_plan_exec_record` → `geo_diagnosis_task`，`geo_diagnosis_plan_exec_record_result` → `geo_diagnosis_task_result`，`geo_diagnosis_result_output` → `geo_diagnosis_task_report`，`geo_diagnosis_result_analysis` → `geo_diagnosis_task_result_analysis`；关联列 `exec_record_id` → `task_id`，分析表 `output_id` → `report_id`、`result_id` → `task_result_id`。实体/DAO/Service 同步为 `GeoDiagnosisTask*`、`GeoDiagnosisTaskResult*`、`GeoDiagnosisTaskReport*`、`GeoDiagnosisTaskResultAnalysis*`，`DiagnosisTaskResultAnalysisStatus/Prompt`、`DiagnosisTaskResultRuleAnalyzer`，实体字段 `taskId`、`reportId`、`taskResultId`；`.test/diagnosis-task-api-test.py` 与有效文档同步。接口与前端不变（仍为 taskId）。`mvn -o test` 367 项 366 通过，唯一失败仍为原有路由测试。023 已于 2026-10-17 在本地 docker 库 colorful_geo 执行（用户已停 8010，执行时无其他连接、无进行中任务或报告）：执行前核对库内约束与索引名与脚本一致，并备份四表到会话临时目录；执行后行数不变（任务 11、采样结果 141、报告 3、分析 33），约束、索引、外键、列注释均为新名，分析→报告→任务关联 33 行全部对上。服务需用新代码重启后才能读写新表；本条之前的记录使用旧表名和类名。未提交。

- 2026-10-17 按用户确认统一诊断报告相关命名（与“诊断结果”及 `geo_diagnosis_result_analysis` 表一致）：`DiagnosisAnswerJudge` → `DiagnosisResultAnalysisPrompt`（内部 `JudgeResult` → `AnalysisResult`，只组装提示词与解析，不调用模型）；`DiagnosisAnswerRules` → `DiagnosisResultRuleAnalyzer`；`DiagnosisAnswerAnalysisStatus` → `DiagnosisResultAnalysisStatus`；注入字段 `judge`/`rules` → `analysisPrompt`/`ruleAnalyzer`，两个测试类同步改名，`诊断报告全景.md` 同步。`DiagnosisReportSummarizer` 保留。只改名不改逻辑；`mvn -o test` 366 项 365 通过，唯一失败仍为原有路由测试。本条之前的记录仍用旧类名，以本条为准。未重启、未提交。

- 2026-10-17 按用户要求统一列表命名与注释：只给列表用的行类改名为 `XxxPageItem`（`ContentOptimizationRecordSummary` → `ContentOptimizationRecordPageItem`，DAO/Service `listSummaries` → `listPageItems`；`DiagnosisPlanBusiness.PlanSummary` → `PlanPageItem`）；与创建/详情共用的 Response 和领域对象不改。各层列表方法注释改为“XX列表（分页）查询”的直白写法，去掉列表语境的“摘要”，删除写错层的说明（如应用层“避免加载正文”），补 @param/@return 及校验/查询分段注释；JSON 字段不变。删除无引用的 `src/test/.../test/WhatsAppDemo.java`，常规 `mvn -o test` 恢复可编译：360 项 359 通过，唯一失败为原有 `LLMStrategyRouterTest.shouldRejectDisabledExactTaskChannel`。Java 17 主代码编译通过；未重启、未提交。

- 2026-10-17 按用户确认统一分页结构：新增 `infrastructure/common/model/PageResponse<T>`，删除 `ContentOptimizationRecordPage`、`ContentPublishRecordPage`、`LlmChannelPage`、`ProjectInfoPage`、`DiagnosisPlanBusiness.PlanPage` 及 `DiagnosisTaskResponse.PageResponse`，8 个列表接口及 4 个 Controller 测试同步；JSON 结构不变，前端无需改。规范写入 `.claude/rules/project-conventions.md`「列表与分页」。`mvn -o test` 360 项 359 通过，唯一失败仍为原有 `shouldRejectDisabledExactTaskChannel`。未重启、未提交。

- 2026-10-17 按用户确认抽取公共能力：新增 `PageQuery`（分页与关键词校验、offset、LIMIT 片段、组装 `PageResponse`），8 个列表 Business 及项目/模型/发布记录/内容优化记录 Service 改为接收 `PageQuery`，删除各自的分页与关键词校验和 `DiagnosisTaskBusiness.validatePage`；分页报错文案统一为“页码不能小于 0，每页条数必须在 1 到 100 之间”。新增 `GlobalExceptionHandler` 统一 400/404，7 个 Controller 删除重复处理，模块特有映射保留；全局 400 也覆盖原先未处理 IllegalArgumentException 的登录与测试接口（原为 500）。`MgmtPlatformLoginController` 错误改为 `ProblemDetail`，`browser-login.js` 只读 `detail`（版本 20261017-problem-detail）。新增 `PageQueryTest` 6 项；`mvn -o test` 366 项 365 通过，唯一失败仍为原有路由测试。规范见 project-conventions「列表与分页」「接口异常响应」。未重启、未提交。

- 2026-10-17 按用户要求表改名（页面与接口仍称“诊断报告”，避免与单条“诊断结果”混淆，用户已确认）：geo_diagnosis_report → `geo_diagnosis_result_output`，geo_diagnosis_answer_analysis → `geo_diagnosis_result_analysis`，分析表 report_id → `output_id`；实体、DAO、持久化服务同步为 `GeoDiagnosisResultOutputEntity/Dao/Service`、`GeoDiagnosisResultAnalysisEntity/Dao`（实体字段 outputId），索引与约束名同步。新增 `022_rename_diagnosis_result_tables.sql`（RENAME 保留数据，已核对库内索引与约束名与脚本一致），`001_init.sql` 改为新表定义，021 历史脚本不改。诊断相关独立 JUnit 58 项全部通过。
  022 已于报告 5 完成后在本地库执行（期间无 PENDING/RUNNING 报告）：两表与 output_id 列、索引、外键、CHECK 均为新名，数据保留（报告 3 行、分析 33 行），旧表名已不存在。8010 仍为旧代码，需用新代码重启后才能读写新表。
  判定失败观察：报告 3 失败 3/5“判定模型不可用或未返回有效回答”、报告 4 失败 1/5“判定模型调用超时”，判定渠道为能链 claude（上限 1400），与此前风险一致。未提交。

- 2026-10-17 诊断报告接口与页面：新增 `MgmtDiagnosisReportController`（POST/GET 列表/GET 详情，错误映射同诊断任务）、`DiagnosisReportBusiness`（名称取任务快照，`GeoDiagnosisAnswerAnalysisDao.countStatuses` 一次汇总多份报告判定进度，汇总只在详情返回）、`CreateDiagnosisReportRequest`、`DiagnosisReportResponse`；`history-report.html` 由占位改为报告列表 + 生成对话框 + 详情（6 个子 Tab，①② 已接入，③–⑥ 待接入）+ 回答原文对话框，新增 `diagnosis-reports.js/css`（v 20261017-report1）；诊断任务详情新增「生成报告」入口（diagnosis-tasks.js v 20261017-report1）。说明见 [诊断报告全景](诊断报告全景.md)「接口与页面」。
  注释审计：控制器各接口与异常映射、业务三个公开方法与批量组装方法（3 段编号）均有说明及 @param/@return/@throws，状态计数用显式 switch 不依赖枚举顺序；页面脚本关键规则（原文以文本节点写入、可生成任务筛选与服务端一致、生成中轮询）有注释。通过。
  验证：Maven 离线 compile；JS 语法检查；诊断相关（应用、领域、控制器、执行器）与 LLM 策略独立 JUnit 97 项 96 通过（新增业务 3、控制器 2；唯一失败为既有 shouldRejectDisabledExactTaskChannel）。未重启服务、未做浏览器与真实端到端验证；判定渠道选择待用户决定（默认渠道“能链 claude”本机不可达且上限 1400）。未提交。

- 2026-10-17 诊断报告判定与汇总落地（计划见用户已批准方案，结构见 [诊断报告全景](诊断报告全景.md)「判定与汇总」）：`LLMStrategyRouter.selectDefaultChannel()`；`GeoDiagnosisReportService` 新增 claimNext（RUNNING 优先续跑）、freezeJudgeChannel、nextPendingAnalysis、saveAnalysis/failAnalysis（仅 PENDING 可写）、finish/fail（仅 RUNNING 可写）、getResult、taskSnapshot、publishedUrls、loadAnswers；新增 `DiagnosisAnswerRules`、`DiagnosisAnswerJudge`（v1）、`DiagnosisReportSummarizer`、`presentation/task/DiagnosisReportWorker`。用户确认报告范围为“一次诊断任务”，报告页按钮与接口下一步做。
  实测发现并修正：竞品名不统一（改为提示词要求预设名 + 解析兜底归一）；原文校验误杀 Markdown 格式差异（比较前去符号，提示词限一句不跨段，保留率 ~50%→31/36）；Set.of.contains(null) 致解析 NPE（改空安全）。默认渠道“能链 claude”在本机内网地址 10.5.0.32 连接超时且输出上限 1400，作判定渠道风险高；qwen3.6-plus 判定 56–115 秒超过 60 秒超时，判定应选不推理快速模型。
  注释审计：新增 4 类及 Service 新方法均有类/方法说明、@param/@return/@throws，多阶段方法编号，规则与降级决策（续跑、名称归一、原文校验、维度归一失败保持原名、汇总失败不重试）有注释；通过。架构：判定、规则、汇总为无 IO 的领域组件，编排在 presentation/task 执行器，持久化与状态在领域服务；无新表、无接口改动。
  验证：Maven 离线 compile；诊断领域、报告执行器、LLM 策略包独立 JUnit 75 项 74 通过（新增 Judge 5、Rules 3、Summarizer 3、Service 2、Worker 3；唯一失败为既有 shouldRejectDisabledExactTaskChannel）；用 qwen-plus 对 6 条真实回答真实判定与汇总并人工核对。未经服务端到端运行（无接口触发、服务未重启），未提交。

- 2026-10-17 诊断报告基础表落地（结构说明见 [诊断报告全景](诊断报告全景.md) 文末“数据结构”）：用户确认报告表 + 回答分析表两张表，判定用默认渠道（分数最高），手动点击生成。新增 `021_geo_diagnosis_report.sql`（`001_init.sql` 同步）、两个实体与 DAO（`insertPendingFromResults` 一条 SQL 按参数口径建立待判定记录）、三个枚举、领域服务 `GeoDiagnosisReportService.createReport`（FOR UPDATE 锁任务；仅 SUCCEEDED/PARTIAL_FAILED；同任务已有 PENDING/RUNNING 报告则拒绝；排除 finish_reason=length；无有效回答抛异常回滚）。可推导信息（问题分组、引用口径、有无来源、是否联网）不建列。
  注释审计：两实体类与逐字段说明、两 DAO 类与批量方法 Javadoc、三枚举类与各项、`createReport` 方法说明含 @throws 与 4 段编号及决策注释，通过。架构：沿用诊断任务“一个领域服务配多个 DAO”，无空 Service 与 XML；DAO 不判断业务口径，状态与排除原因由服务传参。
  验证：Maven 离线 compile 通过；诊断领域服务独立 JUnit 20 项全部通过（新增 4 项）；021 已在本地 MySQL `colorful_geo` 执行建表，回滚事务中验证任务 30 建 5 条、任务 26 成功 72 条中排除 2 条截断建 70 条，唯一键（1062）与状态/情感 CHECK（3819）生效，回滚后两表为空。判定、汇总、接口与页面未实现；服务未重启，未提交。

- 2026-10-17 与用户确认诊断报告结构，落地 [诊断报告全景](诊断报告全景.md)：6 个子 Tab（概览、可见度与推荐、品牌认知、竞品对比、引用与内容、优化建议）、按问题类型与是否提及划分的 4 类分析场景、分析口径（只分析成功回答，引用指标同口径比较）。仅文档，未实现；下一步为指标计算、回答分析结果表与判定方式的详细设计。

- 2026-10-17 按用户要求移除「测试连接」功能，以本条为准替代 2026-10-16 测试连接相关各条：删除 `POST /llm-channels/test`、`LlmChannelTestResponse`、`GeoLlmChannelService.prepareTestChannel`、页面按钮/结果区/脚本/样式及项目约定中的对应条目；`LlmChannelBusiness` 及三个测试文件恢复到加入前版本（0ce1e8f、6be745e、941f187）。保留：API Key 控制台链接、默认输出上限 60000（页面提示改为「需按模型调低」）、请求体字节发送带 Content-Length、流式失败带厂商报错、Chat finish_reason=length 判失败。models.js 版本 20261017-keyurl2，models.css 恢复 20261013-bean3。验证：compile、JS 语法通过，相关独立 JUnit 56 项 55 通过（既有失败同前），源码与约定无测试连接残留。注意：分类器/枚举/测试响应在暂存区为 AD，测试文件 LLMCallFailureClassifierTest 为已提交后删除，提交前需同步暂存删除。未重启，未提交。

- 2026-10-16 按用户要求精简测试连接：删除 `LLMCallFailureClassifier`、`LLMCallFailureType` 及其测试（共 268 行），失败改为直接返回 HTTP 状态码与截断 300 字的厂商原文（HTTP 错误取响应体，其余取异常说明）；`LlmChannelTestResponse` 由 10 字段减为 7 个，Business 内联耗时与截断；页面失败显示「连接失败 · HTTP 状态」与原文，models.js 版本 20261016-test2；项目约定同步。保留：prepareTestChannel、流式失败原因、Content-Length 修复、Chat 截断判失败。上一条中分类器描述以本条为准。验证：compile、JS 语法通过，相关 JUnit 60 项 59 通过（既有失败同前）。未重启，未提交。

- 2026-10-16 输出上限：按用户决定新建渠道默认 60000（配置页初始值与提示、`GeoLlmChannelService.DEFAULT_MAX_TOKENS`、001/007 建表默认值、服务测试断言），原 1400 偏小——库内 122 条成功结果输出均值 1347、P99 5009、最大 5502，37 条超过 1400。Chat 父类遇 `finish_reason=length` 判为失败（与 Responses incomplete 一致），新增测试；历史结果 131、177（能链 claude，上限 1400）已以截断正文成功入库，未处理。渠道 49/50/51 经 API 改为 60000（实测千问、glm-5、ernie-5.0 均接受 60000/65536）；ernie-4.5-turbo 上限 12288 会拒绝 60000。实测发现 DeepSeek 账户 `Insufficient Balance`，渠道 48 需充值。渠道 6（能链 claude，1400）、46、48 未改。验证：compile 通过，相关独立 JUnit 63 项 62 通过（既有失败同前）。服务未重启，未提交。

- 2026-10-16 测试连接暴露两处问题并修复：① 千帆 411 `MissingContentLength`：Spring 6.1 HttpComponents 请求工厂不缓冲，Map 经 Jackson 序列化长度未知而分块发送；Chat 与 Responses 普通请求改为发送 `JSON.toJSONBytes` 字节，流式请求显式设置 Content-Length。② 千问失败原因丢失：根因为渠道 51 输出上限 100000 超过千问 65536（配置问题，需用户改为≤65536，建议 8000）；`parseStreamEvent` 失败事件改为带上厂商 code/message（新增 `describeStreamFailure`），并识别千问不含 type 的 error 事件。验证：Maven 离线 compile；相关独立 JUnit 62 项 61 通过（新增 Content-Length 与失败事件说明断言，唯一失败为既有 shouldRejectDisabledExactTaskChannel）；用项目 RestTemplateConfig 连接池直连真实厂商：千帆成功 10 引用，千问 max=100000 返回具体原因，max=8000 成功 44 引用。注释：两处发送点说明 Content-Length 原因，parseStreamEvent 失败判定注释与 describeStreamFailure 方法说明完整。服务未重启，未提交。

- 2026-10-16 模型配置页新增「测试连接」：`POST /llm-channels/test?id=可选`，用表单当前配置（编辑时密钥留空沿用已保存密钥）经 `LLMStrategyRouter.query(request, channel)` 真实调用一次，问题固定为实时问题以同时验证联网；不写库。分层：Controller 映射 → `LlmChannelBusiness.testChannel`（4 步编号编排，抽出 `buildChannel` 与保存共用）→ `GeoLlmChannelService.prepareTestChannel`（复用 validateChannel、补已存密钥、远程无密钥拒绝）→ 路由器（未改）。失败翻译：新增 `LLMCallFailureType` 枚举（只声明分类与中文建议）与 `LLMCallFailureClassifier`（提取状态码与厂商报错，按有序规则表匹配；余额规则在完整响应体中找关键词并排在状态码前，覆盖 DeepSeek 402、OpenAI 429 insufficient_quota、阿里云 code=Arrearage）。返回 `LlmChannelTestResponse`；页面以文本节点渲染成功/失败区块，models.js/css 版本 20261016-test1。诊断业务、策略、路由器均未改。注释审计：分类器类/classify/extractFacts/readProviderMessage/谓词与三个 record 说明完整，规则顺序原因有注释；枚举各项注释；testChannel 四段编号与失败处理说明；prepareTestChannel 三段编号与 @throws；buildChannel、elapsedMillis、preview 说明；Controller test 方法说明；通过。架构：失败解读放基础设施层，业务层不出现状态码判断；无新中间层。验证：Maven 离线 compile、models.js 语法检查通过；策略包 + 分类器 + 渠道业务/领域/控制器独立 JUnit 57 项 56 通过（唯一失败为既有 shouldRejectDisabledExactTaskChannel）；分类样例取自本会话实测报文（智谱 401、千帆 invalid_appId、DeepSeek 模型名 400、智谱 404）。限制：服务未重启，页面与真实厂商的测试连接未实测；阿里云 Arrearage 与 OpenAI insufficient_quota 样例来自文档格式未实测。未提交。

- 2026-10-16 接入智谱、文心：新增 `ResponsesApiZhipuStrategy`（`/api/v1` Responses，web_search + stream，不发被忽略的 tool_choice）与 `ChatCompletionsApiQianfanStrategy`（千帆智能搜索 `/v2/ai_search/chat/completions`，search_source baidu_search_v2，网页 top_k 10，引用取顶层 references type=web 的 url/title）。Chat 父类 parseResponse 在注解后调用新增 protected `collectStrategyCitations(JSONObject, Map)`（默认空实现）；千问来源提取移入 `LlmCitationParser.collectSearchSources` 与智谱共用，类注释同步。两策略声明控制台地址。项目约定与接入说明按授权同步。实测依据：智谱 Responses 流式 46 秒 33 来源、Chat 方式 glm-4.6 来源全无 link；千帆普通对话 ERNIE 非实时问题不搜索、Responses 无 ERNIE 模型、智能搜索每次 10 条带标题来源。注释审计：Chat 父类 parseResponse 第 2 阶段注释与方法说明、新增钩子说明；两新类及 apiKeyConsoleUrl/buildRequestBody/collectStrategyCitations 方法说明与决策注释；collectSearchSources 说明；均通过。架构：复用两协议父类，无新抽象类，共用解析在既有解析器内。验证：Maven 离线 compile 通过；策略包 + 控制器独立 JUnit 41 项 40 通过（唯一失败为既有 shouldRejectDisabledExactTaskChannel）；真实响应解析：智谱 33 条、千帆两次各 10 条带标题。服务未重启、未经服务链路真实诊断、未提交。

- 2026-10-16 接入千问并在配置页展示 API Key 获取地址：新增 `ResponsesApiQwenStrategy`（Bean `responsesApiQwenStrategy`，web_search + tool_choice required + stream，重写 collectStrategyCitations 取 `web_search_call.action.sources` 中 type=url 网址去重为引用）；`LLMStrategy` 增加默认 null 的 `apiKeyConsoleUrl()`，豆包/DeepSeek/OpenAI 两个/千问声明控制台地址，`/llm-channels/strategies` 增加 apiKeyConsoleUrl，model-config 页面在调用策略下方显示链接（仅 https），models.js 版本 20261016-keyurl1。豆包标准 url_citation 仍由父类解析，未下沉。用户授权修改 project-conventions.md 的引用与策略选项约定。实测：qwen3.6-plus 流式联网成功（30 个来源）；qwen-plus 返回错误；智谱 401（Key 缺 secret 段）、千帆 invalid_appId（Key 无权限），二者未实现。注释审计：千问类/apiKeyConsoleUrl/buildRequestBody/collectStrategyCitations 方法说明与决策注释完整；LLMStrategy 默认方法、控制器 strategies 与 StrategyOption 说明同步；各厂商 apiKeyConsoleUrl 方法说明完整；通过。架构：控制台地址由策略单处声明，经现有选项接口下发，无前端重复表。验证：Maven 离线 compile、models.js 语法检查通过；策略包 + LlmChannelControllerTest 独立 JUnit 36 项 35 通过（唯一失败为既有 shouldRejectDisabledExactTaskChannel）；真实千问响应经新策略解析得 30 条引用。控制台地址未逐一登录核对。服务未重启、未经服务链路真实诊断、未提交。

- 2026-10-16 按用户确认 DeepSeek 引用向豆包看齐：Responses 父类 parseResponse 在文本注解后调用新增 protected `collectStrategyCitations`（默认空实现，豆包/OpenAI/通用不变）；DeepSeek 策略重写为将 `web_search_call.open_page` 网址去掉 `#ws_call_id=` 后按 URL 去重写入 LLMCitation（标题/摘要/位置空），search 检索词不计入，已有注解引用优先。入库、接口、页面、数据库无改动。用户授权在 project-conventions.md 补充 DeepSeek 引用口径；接入说明同步并修正 flash 不联网结论。排查：任务 #28（渠道 48，`deepseek-v4-flash`→`deepseek-flash`）5 条结果无 web_search_call、输入仅 46 token，对照实测 flash 静默不搜索、v4-pro 正常，与地址/参数无关；需将渠道 48 模型改为 `deepseek-v4-pro` 后重新发起。注释审计：父类 parseResponse 方法说明同步、新增第 4 阶段编号说明补充来源；collectStrategyCitations 父/子方法说明、参数与只追加语义完整，open_page 筛选决策有说明；stripWebSearchCallId 说明去重原因及 # 追加规则；均通过。架构：仍在策略层复用父类，钩子仅一个实现，为避免复制解析结果的必要扩展点。验证：Maven 离线 compile 通过；策略包独立 JUnit 28 项 27 通过（新增 open_page 转引用/去重/去标识、仅检索为空、注解优先 3 项），唯一失败为既有 shouldRejectDisabledExactTaskChannel；用 4 份真实 DeepSeek 响应跑真实解析：两份分别得 2、3 条引用，仅检索与 flash 为 0。未重启、未经服务链路真实诊断、未提交。

- 2026-10-15 新增 DeepSeek 官方联网策略 `ResponsesApiDeepSeekStrategy`（Bean `responsesApiDeepSeekStrategy`）：继承 Responses 父类，仅重写 buildRequestBody 加 `web_search`（不带 sources）、`tool_choice: required`、`stream: true`；父类、协议枚举、豆包策略、数据库与页面不变（选项来自 Bean Map）。依据官方 Key 真实 curl：Responses 实际执行搜索并返回 web_search_call（search/open_page），正文 annotations 为空，故引用为空，open_page 留在原始响应；不新增 Anthropic 协议。注释审计：类 Javadoc 说明职责/引用边界/资源归属；buildRequestBody 单阶段，参数与返回完整，不传 sources 与强制搜索、流式两处决策有说明，通过。架构：infrastructure/client 策略层，复用父类 HTTP/SSE/解析，无新抽象或数据对象。验证：ms-17 + Maven 3.6.1 离线 compile 通过；策略包独立 JUnit 26 项 25 通过（新增请求契约、实测结构 SSE 解析 2 项与 Bean 7 个注入通过），唯一失败为既有 shouldRejectDisabledExactTaskChannel，未改动。限制：8010 实例未重启（其渠道 Bean 名仍为旧名，疑似未执行 019/020 或旧版本），未经服务链路做真实调用；DeepSeek 渠道 ID 3 仍是旧配置需在页面改为新策略/`https://api.deepseek.com`/`deepseek-v4-pro` 并填 Key。open_page 是否作为“已读网页”入库待用户确认。未提交。

- 2026-10-15 仅在 parseStreamEvent 的失败事件分支新增 WARN：记录 type、responseId、status、incompleteReason、errorCode，机器字段限定字符和长度，不输出正文/error.message，不加耗时。完整方法注释复核：空事件/DONE→失败或进度→完成响应，原异常和落库逻辑不变；未重启、未提交。

- 2026-10-15 按用户确认只补 readStreamResponse 局部日志：接收开始 DEBUG（HTTP 状态、媒体类型、平台请求 ID），两个正常完成返回点 INFO（耗时、非空数据帧数、回答长度、引用数、结束状态），EOF 未完成 WARN（耗时、帧数、最后事件类型），替换原整包结果日志。平台头和最后事件类型限制字符/长度，不输出正文；无新异常结构、落库或上下文框架。完整方法注释复核：JSON 分支→SSE 分帧/计数→EOF 完整性检查，资源仍由 RestTemplate 管理。编译及 21 项策略/SSE 测试通过；未重启、未真实厂商验证、未提交。

- 2026-10-15 按用户确认撤销最近两轮日志重做和失败报文入库，恢复原异常、日志及失败保存行为；保留 SSE、重新发起接口、页面布局和 SSE 注释。撤销新增日志文档/测试，保留 SSE 测试；主代码编译、21 项策略/SSE 与 26 项诊断测试通过，限定差异检查通过。无关暂存修改保留；未重启、未提交。

- 2026-10-15 为 Responses query 的 stream 分支补说明：显式开关及 null 安全、SSE 与 JSON 媒体类型、持续读事件的目的与超时边界、execute 两类回调、同步读取和响应/连接生命周期。仅注释，无逻辑改动；完整 query 方法注释复核及限定差异检查通过，不新增或重复执行行为测试。

- 2026-10-15 任务详情布局：重新发起按钮移至标题栏右侧，与关闭并排；重发说明移至概览下，仅 FAILED/PARTIAL_FAILED 时与按钮同步显示，执行中不留空操作区。概览取消顶部多余外边距，窄屏按钮允许换行，CSS/JS 版本统一 20261015-restart2。修改 openDetail/refreshDetail 完整方法复核，仅增加说明显隐联动，无接口行为变化；JS 语法和限定文件差异检查通过，未浏览器视觉验证、未重启或提交。

- 2026-10-15 当前任务重新发起：新增 POST /diagnosis-tasks/{id}/restart，任务详情增加「重新发起」。仅 FAILED/PARTIAL_FAILED 主任务可操作；事务内 FOR UPDATE 锁住主记录，非 SUCCEEDED 明细恢复 PENDING 并显式清空调用结果/原因/耗时/开始结束时间，主任务恢复 PENDING 并清空开始结束时间。保留成功结果、原 ID/创建时间/提交标识和所有冻结配置，不直接调用模型，后台按原领取流程执行；渠道变更仍按冻结校验拒绝。并发重复请求等待主锁后会因活动状态被拒绝。编译、26 项诊断相关独立测试（新增状态保护、SQL 条件、缺失/空重试、HTTP 路由及错误契约）、JS 语法和限定文件 diff 检查通过；未验证真实数据库事务/锁并发、未浏览器联调、未重启、未提交。

  注释/架构逐方法复核：GeoDiagnosisTaskService#restart（三段：锁主任务与终态校验→按任务排除成功并清空旧数据→主记录同事务排队，资源/锁生命周期与无模型调用已说明）；DiagnosisTaskBusiness#restart（重排后回读，无新事务）；MgmtDiagnosisTaskController#restart（当前 ID 路由，复用错误映射）；JS refreshDetail/openDetail/重发事件（按钮状态、请求禁重、刷新保护与错误显示）；新增四个测试方法均说明业务边界。全部通过；沿用 presentation→application→domain→DAO，无新表、线程或抽象层。

- 2026-10-14 修复豆包联网诊断约 60 秒失败的已知调用方式差异：统一 HTTP 配置仍为 60 秒，豆包新增 stream=true；Responses 父类在 RestTemplate 回调内逐帧读取 SSE，仅 response.completed 中有效完整 response 经现有正文/引用/用量解析后返回。EOF、DONE 前无完成、失败和不完整事件拒绝；其他策略仍为 JSON，保留明确 JSON 网关响应兼容，不重试、不更换模型、不调整工具与生成参数。Chat/Responses 补请求参数摘要，路由补 callId、成功耗时/长度/引用数、失败类型/根因类型/HTTP 状态；不打印密钥、正文或远端错误体。主代码编译与新目录 21 项独立相关测试通过，含实际 1 秒读取超时下持续 1.6 秒 SSE 流、完成元数据、断流与错误事件。逐方法注释/架构复核及有效说明同步。未真实豆包联调、未重启、未改数据库或提交；首响应或事件间隙仍受超时约束，不能声称所有超时已消除。

- 2026-10-14 模型配置表单将“模型标识”改为“选用模型”，占位和说明明确填写平台调用示例中的 model（模型 ID 或接入点 ID），不是供应商展示名称。仅静态文案，字段及保存行为不变；源码差异检查通过，未做浏览器验证或重启。

- 2026-10-14 当前最终结构以本条为准：按用户最新要求将两个协议客户端改为 strategy 包下 AbstractChatCompletionsApiStrategy、AbstractResponsesApiStrategy；五个具体策略继承对应父类，Common 使用默认实现，OpenAI/豆包只重写 protected buildRequestBody 并调用 super。协议声明、query 校验/组装/HTTP/解析集中到父类，父类不注册 Bean，仍共用原 HTTP 连接资源；六个策略 Bean 名保持不变，无新增迁移。Java 17 编译、全新隔离目录 17 项相关测试通过，含 Spring 继承字段注入、五策略本地 HTTP 和工具隔离；注释/架构逐方法复核及有效文档同步。未全量测试、未重启、未真实厂商联调、未提交；此前 019/020 更名迁移仍未执行。

- 2026-10-14 最新结构：按用户确认抽取 ChatCompletionsApiClient、ResponsesApiClient，共用对应协议的基础请求、配置校验、HTTP 与响应解析；五个策略字段注入客户端，只维护默认或厂商参数，Bean 名与页面契约不变。两个客户端不实现策略接口，仍共用 llmRestTemplateWithTimeout 与 LlmCitationParser，没有新连接池、父类、路由或搜索流程。主代码 148 文件编译、全新隔离目录 17 项相关测试通过，覆盖六个策略 Bean、五策略本地 HTTP、请求隔离及引用/元数据。逐方法注释及架构复核见策略独立实现检查；接入和连接池文档同步。未执行全量测试、未重启、未真实厂商联调、未提交；此前 019/020 Bean 更名 SQL 仍未执行，本轮无需新增迁移。

- 2026-10-14 最新命名统一为“协议 + 厂商 + Strategy”：ChatCompletionsApiCommonStrategy、ChatCompletionsApiOpenAiStrategy、ResponsesApiCommonStrategy、ResponsesApiOpenAiStrategy、ResponsesApiDoubaoStrategy，Spring Bean 同步使用首字母小写的新名称。MockLlmStrategy 无协议厂商组合，保持原名。文件、测试类及引用、页面默认选项、OpenAI 搜索模型识别条件、资源版本和有效策略说明同步；新增 020 SQL，在 019 后迁移数据库 Bean 名，均未执行。历史快照保持原样，升级前先结束旧策略的未完成任务，升级后重新创建。主代码编译、全新隔离输出目录的 17 项相关测试、JS 语法及限定文件 diff 检查通过；首次复用旧测试目录误加载已更名测试的残留 class，换新目录后通过。仅重命名，逐方法注释及架构职责复核无变化；未重启、未真实厂商联调、未提交。

- 升级 019 的边界：诊断执行严格比较冻结渠道配置，历史快照不改写；先结束使用旧通用策略的未完成任务，升级后重新创建，避免 Bean 更名被判定为渠道配置变化。
- 2026-10-14 按用户要求将两个通用策略及 Spring Bean 改为 CommonChatCompletionsApiStrategy / commonChatCompletionsApiStrategy、CommonResponsesApiStrategy / commonResponsesApiStrategy；页面新建默认选择 common Chat，模型页面脚本版本同步更新。测试和有效策略文档引用同步，历史 016 SQL 保留，新加 019 迁移已有渠道 Bean 名（尚未执行，升级前需执行）。仅重命名，无方法职责或协议行为变化，沿用并复核现有逐方法注释和架构审计；主代码编译、17 项策略相关独立测试、JS 语法与本次文件 diff 检查通过。未重启、未调用真实平台、未提交。

- 2026-10-14 按用户要求仅收敛现有策略：删除两个协议抽象父类，五个远程策略直接实现 LLMStrategy，独立维护请求、HTTP 与响应解析；共用具名 HTTP 客户端及移到策略包的引用解析器。Mock 及全部六个 Bean 名、协议、页面选项、数据库值、调用与搜索参数保持不变，无通道或数据迁移；未新增国内平台、策略注册表、搜索接口、工具循环或应用侧搜索资料拼接。响应测试迁到具体策略同包，补请求隔离及 OpenAI Chat 本地 HTTP 回归；Java 17 主代码编译、17 项相关独立 JUnit、源码继承/旧包路径扫描与差异检查通过。有效接入/连接池说明同步，逐方法注释及架构证据见 [策略独立实现检查](策略独立实现检查.md)。本轮未运行全量测试，原有渠道禁用测试分歧和 WhatsAppDemo 缺依赖限制未处理。未重启、未做真实厂商联调、未改数据库或提交；需加载新 Java 类后生效。

- 2026-10-14 按用户确认统一诊断业务术语：注释、字段说明、提示文案和页面不再使用“采样”，整轮称“诊断任务”，一次问题/模型调用称“单次诊断”，保存的记录称“诊断结果”，重复次数称“重复执行次数”。区分“预计 N 次诊断”和“共 N 条诊断结果”，进度仍包含待执行及已结束记录。相关后端/测试注释、异常提示、方案与任务页面及报告占位说明同步；英文方法/变量、接口字段、状态、SQL 与执行逻辑不变。方案/任务脚本及 11 页相关导航版本统一 `20261014-terms1`。逐项核对快照/响应字段、枚举状态、领取/汇总/恢复方法及页面计数语义，沿用原职责与阶段编号，不引入新流程。Java 17 主代码编译、20 项相关独立 JUnit、两份 JS 语法、术语残留与导航版本检查、diff 检查通过；资源随 Maven 编译同步到 target/classes，但未重启服务、未做浏览器或真实平台验证、未提交。历史 SQL 迁移和既有报告未改写。

- 2026-10-14 按用户确认拆分诊断状态枚举：新增 `DiagnosisTaskStatus`（整轮，含部分失败）和 `DiagnosisTaskResultStatus`（单条采样，不含部分失败），类首句概括用途，枚举项及流转边界有注释。查询、更新、汇总和筛选校验均替换；批量初始化由 Service 设置采样初态，DAO 改绑定 `item.status`，不再写死 SQL 字符串。实体及接口状态仍为原字符串，未改表结构、前端协议、事务或恢复语义。Java 17 主代码编译、20 项相关独立 JUnit（含真实 MyBatis SQL 参数绑定、整轮终态、筛选边界、进度统计、执行组件与 MVC 契约）、差异检查通过；未运行全量测试，原 WhatsAppDemo 缺依赖的限制沿用。未重启、未改数据库、未做真实联调、未提交。

  注释与架构复核：两个枚举只有状态定义，无自定义业务方法；使用现有 common/enums 目录，未增加转换层。实体字段仅更新状态来源说明。全部变更方法均已检查职责、分段和决策：

  | 方法 | 实际阶段 | 方法说明 | 分段/编号 | 决策说明 | 结果 |
  |---|---|---|---|---|---|
  | GeoDiagnosisTaskService#create | 校验/锁定 → 冻结任务 → 初始化采样 | 完整 | 三段编号 | 待执行初态、事务与幂等保留 | 通过 |
  | GeoDiagnosisTaskService#insertSamples | 复用快照 → 组装采样 → 批量写入 | 完整 | 三段编号 | 采样枚举初始化、尾批与唯一键保留 | 通过 |
  | GeoDiagnosisTaskService#claimNext | 查询锁定 → 条件领取 | 完整 | 两段 | 仅待执行任务可进入执行中 | 通过 |
  | GeoDiagnosisTaskService#claimSample | 查询锁定 → 设置开始 → 保存领取 | 完整 | 三段编号 | 写入失败不得调用模型 | 通过 |
  | GeoDiagnosisTaskService#succeed | 映射引用 → 组装回答 → 条件保存 | 完整 | 三段编号 | 仅执行中采样可保存成功 | 通过 |
  | GeoDiagnosisTaskService#fail | 条件写入 → 确认保存 | 完整 | 两段 | 不覆盖终态、失败向外暴露 | 通过 |
  | GeoDiagnosisTaskService#finish | 汇总采样 → 决定整轮终态 → 写入 | 完整 | 三段编号 | 未完成拒绝、部分失败仅整轮使用 | 通过 |
  | GeoDiagnosisTaskService#interrupt | 终止未完成采样 → 汇总整轮 | 完整 | 两段 | 保留已有回答、不重发 | 通过 |
  | GeoDiagnosisTaskService#recoverInterrupted | 读取执行中任务 → 逐轮收尾 | 完整 | 两段 | 唯一锁前提、保留已存结果 | 通过 |
  | DiagnosisTaskBusiness#response | 回读/统计 → 渠道进度 → 组装详情 | 完整 | 三段编号 | 未知持久化状态仍按状态损坏处理 | 通过 |
  | DiagnosisTaskBusiness#validateStatus | 空筛选 → 按对应枚举校验 | 完整 | 两段 | 大小写与原值精确匹配、拒绝跨状态域 | 通过 |
  | GeoDiagnosisPlanExecRecordResultDao#insertSamples | 批量绑定并写入 | 完整 | 单阶段不编号 | 初态由 Service 指定，DAO 不另设规则 | 通过 |

- 2026-10-14 按用户要求整理 `DiagnosisTaskResponse`：五个 record 均改为每个组件独占一行，补任务/采样标识、状态、冻结名称、进度计数、分页、JSON 结构、耗时单位、空值及列表/详情差异的字段注释。仅格式和注释变更；五个 record 的职责及字段逐项核对，无业务方法需阶段审计，架构不变。Java 17 独立编译、五个类型的 javap 构造参数/访问器签名对比及 diff 检查通过；未重复运行行为测试、未重启或提交。

- 2026-10-14 按用户确认统一业务 JSON 为现有 Fastjson `1.2.83_noneautotype`：诊断快照/结果、模型协议响应、制题、表单/扫码账号及东方财富草稿解析已替换；Spring MVC 默认转换器和密钥隐藏等 Jackson 注解保留。快照显式保留空值并统一数字比较；引用 record 在落库处显式映射五个字段，避免 Fastjson 1.x 输出空对象。制题异常分类和测试/回归脚本同步。Java 17 主代码编译、除 WhatsAppDemo 外的独立测试编译、平台回归脚本编译、MVC JSON 契约及 diff 检查通过；JUnit 共 302 项，301 通过，唯一失败为原有 `shouldRejectDisabledExactTaskChannel`（路由和测试均与 HEAD 一致，本次未修改）。常规 Maven 测试编译仍受 WhatsAppDemo 缺依赖阻断。逐方法注释及架构证据见 [Fastjson统一替换检查](Fastjson统一替换检查.md)。未改数据库、未重启、未调用真实平台、未提交；使用前加载新 Java 类，渠道启停与精确调用的既有测试分歧另行确认。

- 2026-10-14 维护 `DiagnosisTaskSnapshots` 注释：补齐类职责/入口/边界、方案与渠道组装阶段，纠正生成参数不一定发送的表述，说明策略缺失、JSON 空内容与字面量 null、地址展示范围及摘要空值行为。仅注释变更，未改逻辑、未重启或提交；不新增或运行行为测试。逐方法复核如下（类职责通过；纯注释修改，架构检查不适用）：

  | 方法 | 实际阶段 | 方法说明 | 分段/编号 | 决策说明 | 结果 |
  |---|---|---|---|---|---|
  | plan | 方案信息 → 项目资料 → 问题与渠道 | 完整 | 三阶段编号 | 背景不拼入问题 | 通过 |
  | question | 选择问题字段 | 完整 | 单阶段不编号 | 保留原题边界 | 通过 |
  | channel | 渠道资料 → 策略协议 → 生成参数 | 完整 | 三阶段编号 | 白名单、缺策略拒绝、保留空值 | 通过 |
  | read | JSON 解析与结果判定 | 完整 | 单阶段不编号 | 损坏失败，NullNode/MissingNode 交调用方判断 | 通过 |
  | safeUrl | 空输入处理 → 展示地址重建 | 完整 | 两阶段分段 | 未填写与无效地址区分、清洗范围 | 通过 |
  | digest | 完整地址摘要 | 完整 | 单阶段不编号 | null/空串一致、独立密钥不参与、算法缺失失败 | 通过 |

- 2026-10-14 按用户确认改造 .claude 加载与验收体系：CLAUDE 只保留优先级/路由/加载入口；通用注释与 Java 硬规则收简且保持两阶段分段、三阶段编号、业务决策和生命周期要求。新增 workflows/code-change-flow.md，合并 A/D/E 的代码加载、实现、逐方法注释审计、架构检查与验证证据；04/09 只维护实现/复核职责，architecture 引用项目实际约定，不再维护另一套分层。清理各角色/流程的自动加载假设、过时章节引用及强制五个英文输出栏目；多场景按用户目标路由，不强制用户选择内部角色或启动多代理。规则索引复用已读且未变正文，截断须补读，Markdown 引用不算加载。61 文件/7539 行收敛为 62 文件/6598 行（新增公共流程），入口 278→50 行。所有实际本地 Markdown 链接有效（排除技术设计模板占位符），diff 检查通过；Java线程改造、前端修改、普通问答三类加载/验收路径走查通过。未修改业务代码、未修改 memory、未提交；本轮不重跑代码测试。仅规范和执行证据约束，不声称自动保证注释语义无遗漏。

- 2026-10-14 按用户指出的注释缺漏，逐方法核对 DiagnosisTaskWorker：补类职责/入口/边界、执行状态字段语义，poll/tick/execute/acquireLease 的编号主流程，以及模型失败、空回答、落库中断、异常分类和锁释放决策注释；补 SQLException 方法说明。仅修改注释，未改变执行逻辑；diff 检查通过，未重复跑测试、未重启或提交。

- 2026-10-14 Worker 按用户确认改用 @Resource(name="diagnosisTaskExecutor") TaskExecutor：应用就绪仅提交一次后台轮询，按执行后间隔一秒顺序运行；删除 ScheduledExecutorService、Executors、PostConstruct 初始化和自建线程池关闭逻辑。停机只标记停止并中断本组件线程，不关闭共享线程池；数据库会话锁由执行线程退出 finally 释放，避免调用未结束提前接管。补重复启动只提交一次、排队中关闭不领取任务两项测试。diff 检查通过；主代码编译被当前工作区 GeoDiagnosisTaskService 调用已不存在的 router.validateChannel 阻断，相关测试未能运行，不恢复用户路由改动。未重启、未提交；先解决路由调用不一致再复验。

- 2026-10-14 按用户确认收简模型响应：LLMResponse 改为 Lombok 普通数据类，保留正文/引用及诊断需要的模型、用量、结束状态、原始响应字段；删除 withMetadata、withoutCredential、递归脱敏和密钥字符串替换。现有两个 HTTP 模板直接解析 JSON 并显式填充数据，未新增包装层；问答/内容优化/问题生成/诊断落库等调用方同步改 getter。原问答 JSON 仍只输出 content/citations，原始响应现在按厂商原报文存储，不再承诺脱敏，页面提示和有效接口说明同步。Java 17 主代码编译通过；独立编译除原 WhatsAppDemo 外的测试并运行成功，原 Maven 全量测试仍受该演示依赖限制。未重启服务，未做真实厂商联调，未提交。本条替代此前关于响应脱敏的实现描述。

- 豆包响应适配已按用户提供的完整 JSON 核对：现有 RestTemplate 直接 HTTP 请求方舟，未使用 OpenAI SDK；共享 Responses 模板继续映射最终正文、引用标题/URL、实际模型、usage（含搜索工具次数）、completed 状态与脱敏原始响应。引用解析补 summary 到 snippet 映射，缺少 summary 时保留原 text 兼容；site_name/publish_time 等字段保留原始响应，不增加通用模型字段。新增 2 项本地报文回归验证忽略推理/工具调用正文、引用缺少位置保留 null、用量/原始字段完整保留与 text 兼容。Java 17 编译及合计 8 项相关独立 JUnit 通过；未验证真实厂商，未重启服务、未提交。用户 curl 的 stream:true 是 SSE 模式，本项目继续非流式 JSON。

- 豆包搜索策略已按用户确认补齐 `tools.web_search.sources = ["doubao"]`，保留 `tool_choice: required` 和非流式完整 JSON，模型标识仍来自数据库配置；类/方法/适配注释与大模型联网接入说明同步。新增豆包请求契约测试，覆盖来源、强制工具、模型、独立问题、输出上限及不启用流式；Java 17 主代码离线编译通过，独立 JUnit 运行该测试、Spring Bean 路由测试、HTTP 连接与引用回读测试，共 6 项全部通过。未调用真实厂商、未改渠道或任务数据、未重启服务、未提交；需重启加载新策略，再用已开通模型小规模验证实际引用。

- 诊断任务详情交互已按用户确认调整：任务详情保留弹窗，回答从底部展开改为独立弹窗；冻结配置、Token 用量、原始响应均为只读子弹窗。冻结配置按本轮概览、项目资料、问题列表、模型配置分组，Token 明细使用中文标签并保留实际字段路径与零值，原始响应格式化 JSON。阅读子弹窗时不重建回答列表，终态回答不反复刷新；关闭返回原入口，Escape 只关闭顶层，全部关闭后解除页面滚动锁。11 个页面导航及任务 JS/CSS 统一 task2。使用 8016 临时只读代理加载最新静态资源、读取 8010 现有任务 #26 验证四类弹窗、返回焦点、滚动锁与 393px/1280px 布局，无新建任务/模型调用/数据库写入；JS 语法、7 项字段展示断言、diff 检查通过，浏览器无错误。预览见 `.test/diagnosis-task-dialog-preview.jpg`。未重启或替换 8010 原实例，实际页面需重新加载最新静态资源；临时代理验收后停止，未提交。

- 诊断任务第一版已按用户确认实施：新增 `/diagnosis-tasks` 创建、列表、详情、采样分页和单条回答接口；事务冻结项目/问题/非敏感渠道快照，requestId 强制校验与并发去重，结果按 250 条批量初始化。后台单线程逐条执行，数据库会话锁限定唯一执行者；精确渠道 ID 调用、密钥轮换兼容、配置变化拒绝、失败继续、空回答失败、保存失败停止、重启保留已存回答并终止不确定采样均已实现，无自动重试/换模型/Mock 降级。模型响应补实际模型、用量、结束原因和脱敏响应体，普通问答 JSON 保持 content/citations。任务页面接入创建预览、实时进度、渠道统计、分页回答、引用与用量；11 个导航入口及任务 CSS/JS 统一 task1，方案页提示同步。验证：Java 17 编译、292 项独立 JUnit、9 组真实 API/MySQL 场景、重启恢复、浏览器 24 次采样/第二页/回答详情与宽窄布局、JS 语法/diff 检查通过；常规 Maven 测试仍受原 WhatsAppDemo 缺依赖阻断。共享业务库存在其他执行会话，新旧版本混用曾误判配置；未停止该会话，后期完整验收迁至临时隔离库。测试模型为本地 HTTP 替身/显式 Mock，真实厂商权限和质量待验证。原 IDE/旧 jar 未替换，实际使用前统一共享库实例版本。详见 [功能与接口](诊断任务功能与接口.md)、[回归报告](../.test/诊断任务功能回归报告.md)。验收结束清理临时资料/触发器/隔离库与 8016 实例；暂存发布客户端代码保留，未提交。下一步小规模真实模型验收，再规划诊断报告；本条替代下述“执行服务/页面未实现”的旧快照。

- 两张执行表 ORM 已完整核对并验证：`GeoDiagnosisPlanExecRecordEntity/Dao` 与 `GeoDiagnosisPlanExecRecordResultEntity/Dao`，字段映射原已完整，本轮补齐双实体字段语义、JSON/空值/时间说明及 DAO 职责；JSON 保留 String，不增加转换层。Java 17 主代码离线编译通过，独立 MyBatis-Plus 工厂与真实 MySQL 验证两个 Mapper 的增删改查、自增主键回填、快照/响应/时间字段及默认值、两个唯一键均通过。独立验证程序初次默认事务配置造成临时项目/方案残留，已按 ID 精确清理；改用显式 JDBC 事务后复验通过，临时项目、方案、任务和回答无残留。此条替代下条“未验证 DAO 运行时调用”；未重启服务、未验证 Spring 容器内业务事务、未实现执行接口或模型调用，未提交。下一步仍为任务创建与执行逻辑。

- 用户确认先落实诊断任务表结构：本地 MySQL 已核对两张执行表及快照字段，新增 `request_id`（VARCHAR(64)、ascii_bin、历史可空）与唯一索引，完整快照注释补项目资料。018 已本地执行，001/011 与执行记录实体同步；任务接口后续必须提供 requestId。真实事务验证快照、回答、引用和用量回读、请求/采样唯一键、状态/重复次数约束及历史 NULL 标识兼容，全部通过，测试数据回滚后两表仍为零条。使用 IDEA 内置 Maven 与 Java 17 离线主代码编译通过，diff 检查通过；未跑全量单测、未重启服务、未验证 DAO 运行时调用、未调用外部模型。迁移已有 011 的库只执行 018 一次，新库用最新版 001/011，不重复执行 018。详见 [诊断任务实施计划](诊断任务实施计划.md) 和 [诊断方案设计与接口](诊断方案设计与接口.md)。下一步待确认后实现创建快照与指定渠道执行；自动重试、取消、定时和报告后续处理。暂存发布客户端改动保留，未提交。

- 用户确认去掉宽泛的模型前缀判断：删除 temperatureExcludedModelPrefixes 及接口字段，随机性改为可选，留空提交/保存 null 且不发送，填写原值由接口校验。删除后台补 0.3；实体温度字段允许清空，启停改为只更新 enabled 避免连带清空温度。017 已本地执行并同步 001/007，已有值保留；真实 MySQL 事务验证 NULL 写入后回滚。bean6 页面及构建资源已更新，29 项相关独立 JUnit、Node VM 和 diff 检查通过。Java 服务尚未重启，旧服务仍可能补默认值，使用新规则前须重启。此条替代上轮前缀过滤，无外部模型联调。

- 按用户要求，大模型 strategy 及 template 目录的单行 if 分支统一补齐花括号，包括 throw/continue/return 和参数赋值；只调整写法，逻辑不变。主代码打包与差异检查通过，本次未重复跑测试。

- 纠正 Responses 一律禁用生成参数：模板发送 max_output_tokens 和通常的 temperature；OpenAI 已知推理前缀 gpt-5/o1/o3/o4 当前不传随机性（没有推理模式配置），仅此厂商适用，兼容/豆包正常传参。策略接口返回同一份前缀，页面 bean5 读取规则，避免前后台重复维护；旧服务缺字段时要求重启。构建、28 项相关独立 JUnit（实际 HTTP 参数及引用回读）、Node VM 表单与 diff 检查通过；资源已同步，Java 服务未重启、无数据库变更、未做真实厂商联调。此条替代上一轮 Responses 全禁用规则。

- 修复模型页面与真实请求不一致：移除渠道超时输入及 timeoutSeconds/sslVerify 提交；生成随机性/token 仅普通 Chat 可编辑并发送，Responses/Mock/OpenAI gpt-5-search-api 当前不发送，页面禁用并明确提示。制题超时错误改为检查统一 HTTP 配置。bean4 资源已在 8010 HTTP 回读确认；主代码构建、28 项相关独立 JUnit、Node VM 表单切换/提交、JS 语法和差异检查通过。没有新增数据或真实模型调用，新 Java 错误提示需重启；方案渠道有效性检查本次未扩展。

- 本轮模型/方案创建评审：确认渠道 HTTP 参数已不生效但页面仍可填写、Responses 生成参数未发送、制题超时提示仍指向渠道配置、方案保存只核验启用未核验实际策略存在。详见 [模型与诊断方案创建检查](模型与诊断方案创建检查.md)。8010 三个列表 GET 200；未修改业务源码或新建数据，未做本轮创建/外部模型联调。

- 菜单规则按用户最新要求调整：各页面仅为当前高亮链接所在分组添加 open，其他组默认收起。已同步 target/classes，HTTP 验证 8010 全部 11 页面均仅展开当前所在分组；无需脚本或状态缓存。此条替代此前全部收起规则，旧 jar 实例仍未重启。

- 用户反馈菜单未生效：实测 8010/8014/8015 仍返回旧 HTML（4 组均 open）。已运行 Maven resources:resources 更新 IDE 8010 使用的 target/classes 资源，HTTP 回读全部 11 页面/44 组无 open。8014/8015 是旧 jar 实例，未重启，仍使用旧菜单；未做浏览器点击验证。

- 管理后台 11 个页面的 44 个侧栏菜单分组已去掉 open 属性，初次加载默认收起，仍可点击展开，保留当前菜单高亮。未引入状态缓存或脚本；源码扫描确认没有默认展开残留，差异检查通过。本次未执行浏览器验证，运行服务需加载最新 HTML。

- 移除 RequestConfig.Builder 已弃用的 setConnectTimeout，连接建立超时改由连接池 ConnectionConfig 管理；获取连接/响应超时仍在 RequestConfig。配置值及调用契约不变，Java 17 打包及差异检查通过，本次未重复运行测试。

- 用户进一步要求提前注册具名 Bean、使用处直接注入：已改为 @Bean("llmRestTemplateWithTimeout") 和 llmHttpClient，两个协议模板 @Resource 按名称注入。删除动态创建方法、默认 Bean 和双客户端生命周期代码；超时/证书统一取 application.yml 的 http.llm。渠道 timeoutSeconds/sslVerify 仍保留但不影响 HTTP，页面清理待后续处理；主代码构建、25 项相关独立 JUnit 和差异检查通过。无数据库迁移、服务未重启。此条替代下述动态超时/双池说明。

- 已按用户确认改为 infrastructure/config/RestTemplateConfig 共用 RestTemplate + HttpClient 5 连接池，删除 LlmHttpClientFactory。默认 Bean 和按超时创建的轻量客户端共用池；证书策略分池、默认校验、禁用重试/重定向/Cookie，Spring 关闭资源。两个协议模板 @Resource 注入，认证/请求体/引用规则不变。Java 17 构建、25 项相关独立 JUnit 通过（实际 TCP 复用、独立超时及协议 HTTP 回读）；常规测试仍受 WhatsAppDemo 依赖阻断。本轮未重启服务、未改数据库、未做真实模型/TLS 联调，见 [共用HTTP连接池](共用HTTP连接池.md)。

- 按用户要求补齐 strategy 及 template 共 12 个 Java 文件的类、方法、参数和关键流程注释；说明通用 Bean 用途、厂商搜索参数边界、Mock 非自动降级、引用来源和证书配置。修正模板的旧 SDK/官方渠道描述，并如实注明当前 Responses 基础请求不发送生成参数。本轮仅注释变更，主代码打包及差异检查通过；之前包迁移导致的 5 处测试访问错误仍待修复，未宣称测试通过。

- 用户将协议模板/接口/共用工具移入 strategy.template，主源码引用同步且 Java 17 跳过测试打包通过；LlmSearchRequestTest 独立编译确认 5 处跨包访问错误。仅做评审，尚未修复测试；建议厂商参数通过本地 HTTP 检验，解析测试迁到 template 同包，不放宽生产方法权限。数据库/Bean 名无需改动，见 [策略模板迁移检查](策略模板迁移检查.md)。

- 模型列表已拆分渠道名称/渠道编码，新增接口协议独立列，策略列展示 Bean 名。协议使用接口推导值，不增加数据库字段；未知策略显示“策略未注册”。表头/行/空状态统一 10 列，保持横向滚动，移除旧编码副标题样式；模型脚本和样式版本 20261013-bean3。JS 语法及差异检查通过，本次未执行浏览器验证，运行服务需加载最新静态资源。

- 清理复查：删除无引用的 LLMProviderName。发现 Responses 请求未使用页面配置的 temperature/maxTokens、末尾斜杠处理与 Chat 不一致及旧注释/文档残留，详见 [大模型清理复查](大模型清理复查.md)。这些问题尚未修复；本次仅引用扫描和差异检查，未重新构建或联调。

- 当前设计已收敛：geo_llm_channel 直接保存 strategy_bean_name，LLMStrategyRouter/配置校验/页面选项使用 Spring 注入的 Map<String, LLMStrategy>。自建 LLMStrategyRegistry 及 strategyType/strategyCode/strategyName 方法全部删除，不反射、不手工注册、不降级。
- LLMStrategy 只保留 query 和 protocolType。两个协议父类复用 HTTP、基础请求与引用解析；实际子策略处理厂商参数。已按用户要求删除策略名称枚举及 strategyName 响应字段，策略选项只返回 beanName/protocolType；模型列表和诊断方案展示直接使用 Bean 名。重命名 Bean 要迁移数据库。未知 Bean 在保存/调用前拒绝，查询仍可回读并修复配置。
- 本次静态版本 models 为 20261013-bean2，diagnosis-plans 为 20261013-plan12；Java 17 构建、33 项相关测试（含 MVC 接口不再返回名称字段）、两个 JS 语法检查及 diff 检查通过。无数据库迁移，运行实例尚未重启，未做本轮浏览器或外部厂商联调。
- 路由进一步清理：selectOne + 固定 LIMIT 1 选渠道，直接把 GeoLlmChannelEntity 交给策略，删除 LLMConnectionConfig、配置复制、单独密钥传参和解析方法。远程空密钥、未知 Bean、已启用筛选、score/ID 顺序、失败不降级均保留。实体密钥仍不参与 toString 或接口响应。
- 删除未使用的 LangChain4j HTTP 方法及三个依赖；测试入口直接调用 LLMClient，删除 TestLLMBusiness、LLMQueryService 两层纯转发。保留 LLMClient 的异常边界和两个协议父类，不为减少文件数破坏职责。
- 六个实际 Bean：compatibleChatCompletionsApiStrategy、compatibleResponsesApiStrategy、openAiChatCompletionsApiStrategy、openAiResponsesApiStrategy、doubaoResponsesApiStrategy、mockLlmStrategy。普通兼容与原生联网行为保持不变；Kimi/DeepSeek 独立检索未实现，不建立空厂商策略。
- 016 迁移已本地执行，旧 strategy_code 改名并映射成 Bean 名；原六条渠道及凭据、地址、模型、启用状态保留，仅 ID 6 启用。001/007 同步新字段，历史执行快照不修改；新库不要执行旧协议/编码增量。接入与迁移见 [大模型联网接入说明](大模型联网接入说明.md)。
- 页面保存 strategyBeanName，选项来自实际 Bean Map，直接显示选中 Bean 名和推导协议；资源版本 bean1。8014 已重新启动加载新版。真实 MySQL/API 验证通过，浏览器创建/列表/编辑回读通过；临时渠道 ID 16 已按唯一标识清理。
- Java 17 clean 后打包通过，29 项相关 JUnit 全部通过，新增远程空密钥调用前拦截；覆盖 Spring Map、渠道筛选、单次查询、失败不换模型与 HTTP 引用回读。无被删除类/SDK的代码残留引用，diff 检查通过。常规全量测试仍受 WhatsAppDemo 无关依赖阻断，相关测试使用独立编译/JUnit Launcher。
- 8015 加载路由清理版；真实 MySQL/API 创建高优先级 Mock 临时渠道并验证原 /test/llmQuery 成功，不调用外部模型，临时渠道 ID 17 已精确清理。8014 仍是上轮 Bean 配置版，原 IDE 未重启；本轮无数据库迁移。真实厂商搜索仍待联调。
- 原 IDE 实例未重启，需重启读取新表结构。真实厂商账号权限、联网执行与引用质量未验证；用户已暂存的发布客户端改动保留，未提交。

- 新增 geo_diagnosis_plan_exec_record、geo_diagnosis_plan_exec_record_result：一次执行与逐次回答，配套完整表名对应实体/DAO，011 增量与 001 初始化同步，本地 MySQL 已建表。快照 JSON 不包含凭据；结果唯一键为执行记录＋问题序号＋渠道＋重复序号。问题/渠道 ID 不加外键，避免方案编辑重建问题影响历史结果；执行记录与方案、结果与执行记录使用限制删除的外键。
- 本轮范围仅存储结构，未新增执行服务、接口、定时调度或页面。Java 17 离线编译成功，真实 MySQL 事务内插入并回读两层记录及 JSON 成功，测试数据已回滚，diff 检查通过。运行实例未重启，尚未验证 DAO 运行时读写。下一步实现创建执行记录、冻结非敏感快照及逐次调用落库。

- 查看方案的信息区改成浅色紧凑卡片：名称、项目、重复次数三列，渠道占整行；窄屏单列，维持只读。资源版本 plan8，JS 语法与差异检查通过。

- 问题表进一步压缩：输入框默认高度 30px，单元格上下间距 4px，下拉框同步缩小；保留正文滚动与手动拉高。资源版本 plan7。

- 候选问题表的「选用」列移到最左侧，列宽同步调整，方案问题表维持原顺序；资源版本 plan6。JS 语法与差异检查通过。

- 最新页面规则：列表分开「查看」「编辑」；查看以纯文本展示配置和问题，不提供修改或保存入口，仍支持分页。创建与编辑 API 保存成功即自动关闭弹窗并刷新列表；保存失败保留输入，列表刷新失败单独提示已保存。静态版本 plan5。
- 本轮浏览器实测：查看问题区无输入控件，生成和保存入口隐藏；临时方案 ID 7 的创建、编辑均保存后自动关闭，重新查看确认编辑内容。临时测试方案已清理，用户方案 ID 5 保留。JS 语法及 git diff --check 通过。

- 用户已确认实现方案页面和后台，关联项目、独立问题集合、显式被测渠道和重复次数；本轮不实现任务执行和报告。
- 已实现 /diagnosis-plans 创建、编辑、详情、名称筛选与分页；/generate-questions 基于项目资料生成候选，仅返回、不落库。页面支持审核、勾选加入、人工问题、来源/意图/品牌范围/语言编辑、删除、去重和采样规模预览。
- 本轮按用户要求把创建、查看／编辑改成原生 dialog 弹窗，主页面保留列表；支持遮罩、内部滚动、固定头部与操作区，弹窗内显示消息和未保存确认。后台接口不变。静态版本 plan2，8013 运行资源已同步。
- 弹窗验证：创建窗口、窄屏布局及未修改时 Escape 关闭已实际验证，JS 语法及差异检查通过。验证旧 window.confirm 时原生提示阻塞浏览器输入，已替换为弹窗内确认；旧提示需要用户点击取消。替换后的确认及弹窗保存／回读交互尚未完成复测，不宣称全部浏览器回归通过。
- 数据为 geo_diagnosis_plan、geo_diagnosis_plan_question、geo_diagnosis_plan_channel，010 增量 SQL 和 001 初始化同步，本地 MySQL 已执行。聚合事务保存，编辑锁定方案后替换集合；子项失败整体回滚。
- 沿用 @Resource、显式字段映射、完整表名对应实体/DAO。原空 QuestionGenerateBusiness 删除，QuestionGenerateService 已实现实际制题；架构和接口见 [诊断方案设计与接口](诊断方案设计与接口.md)。
- 验证：全量 266 项单元测试通过；真实 MySQL/API 创建、修改、回读、筛选、非法输入与 404 通过。临时触发器模拟子项插入失败，验证原方案、原问题、原渠道及更新时间完整回滚；触发器已清理。
- 浏览器验证创建、保存、编辑回读、人工问题保留、候选勾选和去重、采样量更新。候选成功链路由本地 OpenAI-compatible 测试替身验证，不算真实模型成功。现有 custom 渠道真实调用失败，异常为 SSLHandshakeException，未关闭证书校验或修改用户渠道。
- 验证结束后所有临时 QA 方案、渠道、触发器已按精确标识清理，本地方案表为零条；本地模型替身已停止。SQL 显式设置 utf8mb4，本轮三张表的中文注释已修正并核验。
- 原 IDE 8010 未重启，新 Java 接口需重启后使用；独立 8013 保留运行供页面预览。用户已暂存的头条、小红书改动保留，未提交；最终 266 项测试、JS 语法、git diff --check 均通过。
- 下一步：恢复真实模型渠道连接后验证问题质量；后续任务冻结项目、问题及非敏感渠道配置，独立执行采样，不向被测模型注入项目背景。
- 生成失败截图排查更新：当前默认 custom 渠道重新实测 HTTP 200，返回 12 条经校验的真实候选，仅生成、不落库。先前日志的 SSLHandshakeException 不能证明截图那次的确切原因。接口现按异常类型区分 HTTPS、无法连接、超时和候选格式错误，按钮旁保留实际安全提示，不输出远程异常原文。静态版本更新 plan3。
- 问题集合改为紧凑行内表格：正文、来源、意图、品牌范围、语言和删除在同一行，候选区沿用同样布局；问题每页 20 条并支持跳页，完整集合仍一起保存，新建问题或追加候选跳至新增位置。静态版本 plan4，8013 已同步。
- 使用 100 条临时问题验证 20 行一页、直接跳第 5 页、首条跨页修改、整体保存及关闭；API 回读确认 100 条完整保留。当前仍每次生成最多 30 条，可多次追加，单个方案最多 200 条，本轮未改变生成接口规模。用户方案 ID 5 保留；临时 QA 方案 ID 6 在验证后精确清理。
- 错误提示验证：全量 269 项测试通过，新增覆盖嵌套 TLS 错误、超时、非法候选与异常原文隔离；JS 语法和 diff 检查通过。自有 8013 已重启加载新处理器，实际 HTTP 502 和 plan3 资源已验证；没有修改用户模型配置，没有关闭证书校验。

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

- 当前入口文字统一为“打开草稿”。公众号仍为普通后台链接，不能直达指定草稿。用户明确要求删除独立预览流程：已移除专用 Controller、Service、模型、截图读取、弹窗脚本及专用测试。后续新增流程必须先简短说明范围并取得确认，不绕开现有标准流程。

- 内容详情、发布列表及发布详情通过共用 `draftEntry` 展示“打开公众号草稿箱”（有平台草稿 ID 时）。实际目标为 `https://mp.weixin.qq.com/`，用户在自己的浏览器登录后台后进入草稿箱按标题查找，不宣称是具体草稿深链；不携带 token，不改数据库 `draft_url`。其他平台原链接逻辑保持不变。
- 4 项行为断言及 3 个前端脚本语法检查通过。针对用户反馈的 `draftEntry is not defined`，内容页和发布页的共用脚本、调用脚本统一增加 `v=20261010-1`，避免旧缓存混用。同步 Maven 构建资源后，以真实 8010 发布列表验证脚本加载及公众号入口；未执行全部管理页面回归。


### 效果回收导航归属（2026-10-10）

- 六个管理页面统一将“效果回收”移入“内容投放”，位于“发布记录”之后；效果回收页面面包屑同步改为“内容投放 / 效果回收”。
- 真实 Chrome 复现旧 HTML 缓存导致内容页仍显示原分组；服务返回页面与源码一致，加版本参数后显示正确。六页导航与首页链接统一增加 `v=20261010-nav1`，同步 `target/classes` 静态页面至运行服务。
- 已在真实 8010 服务逐页点击六个导航入口，核验分组、顺序、当前页选中状态及效果回收面包屑通过，并检查页面截图。效果采集仍为待接入占位能力；已经打开的旧页面需刷新或通过带版本链接进入。

## 项目基础档案（Codex，2026-10-10）

- 当前范围已完成：一级“项目管理”及“项目创建／项目列表”，创建基础档案、按名称分页查询、查看当前页完整资料。尚未增加编辑、删除或监测／分析任务；后续任务可引用项目 ID。
- 分层为 MgmtProjectInfoController → ProjectBusiness → GeoProjectInfoService → GeoProjectInfoDao / GeoProjectInfoEntity；已删除无独立行为的 Project 中间模型，Service 直接校验、保存和查询实体，Business 显式转换 ProjectInfoResponse，接口字段不变。创建输入使用 CreateProjectRequest，ID 和时间由数据库生成。业务及 Controller 同步使用 @Resource。
- 简化验证：删除实体二次拷贝、getProject 查询包装与 Service 的反向转换，保存后直接 getById 回读；全量 260 项测试及差异检查通过，覆盖创建与非空列表响应的 ID、列表资料、时间一致性。数据库结构和记录未修改，运行服务未重启，本轮未做真实接口联调。
- geo_project_info 保存五项必填（名称、资料 URL、行业、核心功能、目标用户）与图中七项可选配置；别名、竞品、关键词为 JSON 列表，ID 自增，时间由数据库生成。URL 只保存且校验 HTTP(S)，不抓取页面。001 空库脚本和 006_geo_project_info.sql 均包含建表定义，docker/README.md 已说明迁移。
- 最新表名调整：本地执行 009_rename_geo_project_info.sql，将 geo_project 重命名为 geo_project_info，原 1 条项目记录保留。实体、DAO、Service 及测试同步 GeoProjectInfo 命名；接口地址、请求和响应字段不变。全量 clean test 257 项及差异检查通过。运行中旧服务需重启，否则仍查询旧表；本轮未重启用户服务。
- 本地数据库已执行 006，仅添加项目表。15 项定向测试通过，离线编译、JS 语法和差异检查通过。独立 8012 服务禁用 SQL 初始化，真实创建返回 201、ID 和时间；名称查询、JSON 列回读、分页排序及输入 400 验证通过。Chrome 实际创建后跳列表、详情、名称查询、无匹配／无项目状态通过；八页导航实际点击和高亮通过，截图核验后补齐 contents.css 共用按钮与分页样式。
- 两条明确 QA 项目（ID 1、2）已按 ID 与测试名称精确清理，项目表当前空；未操作已有内容、发布记录或平台草稿。原 8010 服务未停止，新增 Java 接口须重启原服务加载；独立测试服务完成后停止。导航版本统一为 v=20261010-project1，避免旧 HTML 导航缓存。
- Java 注释检查：新增类均说明职责与边界；创建、资料校验和应用编排按业务阶段添加注释；查询、转换、字段规则、URL 校验及错误映射有方法说明。后续任务关联时再按任务设计新增 project_id，不把执行结果放入基础档案。

## 资产诊断页面入口（2026-10-11）

- 页面范围：全部管理页的资产诊断侧栏统一为“诊断方案、诊断任务、诊断报告”。保留 diagnose.html 与 history-report.html 地址，新增 diagnosis-tasks.html，导航版本为 20261011-diagnosis1。
- 三页文案已按 GEO 诊断职责调整，移除内容健康、发布链路、登录态检查等旧规划。页面明确标记能力待接入，未新增诊断接口、任务表或真实报告数据。
- 验证：9 个管理页静态导航、链接顺序与唯一选中状态检查通过，git diff --check 通过。在 localhost:8010 真实浏览器点击验证方案、任务、报告入口，标题与内容正确，并查看报告页截图确认沿用现有样式；尚未接入的业务能力未进行联调。

## 基础配置与模型管理（2026-10-11）

- 最新单次请求简化：LLMQueryRequest 改为 prompt、channelCode、modelName，删除 LLMMessage 与 LLMMessageRole；保留非空问题校验和原文，不携带历史上下文。Chat Completions 直接构造一条 UserMessage，Responses 直接组装一条 user input；删除角色转换方法及静态 userMessage 工厂，LLMClient.query(String) 直接创建请求。搜索与引用规则不变。全量 258 项测试通过，包含无效 prompt、原文保留、Responses 单条输入验证；真实外部模型联调未验证，运行服务未重启。此前文档中的 messages 请求结构已失效。
- 输入类型命名统一：CreateProjectCommand → CreateProjectRequest、SaveLlmChannelCommand → SaveLlmChannelRequest，文件、Controller、Business、测试及参数名同步，接口 JSON 字段不变。Request/Response 与独立业务 Command 的命名边界已写入 AGENTS.md。离线 clean test 全量 257 项通过，src 无旧类名引用，差异检查通过；运行服务未重启。
- 字段映射清理：模型 Business 两处、项目 Business 一处、项目 Service 两处 BeanUtils.copyProperties 已全部改为显式赋值，删除相关 import；保持原字段与接口行为，模型响应不包含 apiKey，未新增转换框架。src/main/java 已无 BeanUtils/copyProperties 使用。全量 257 项测试及差异检查通过；运行服务未重启。
- 最新可读性自检：模型保存删除无意义的密钥校验嵌套，保存结果用明确变量代替取反三元表达式；分页拆开偏移量、查询转换和总数；Service、路由转换参数改为 channel/config，引用测试 JSON 改用文本块。移除 LLMClient 选择前重复日志，实际渠道日志仍由路由器记录。保留响应密钥隔离、协议连接快照、保存回读数据库时间、只更新启停字段这些实际边界；本轮无新增业务抽象。全量 257 项测试及差异检查通过，运行服务未重启。
- 最新请求约定：已删除 LLMQueryRequest.webSearchEnabled；请求仅含 messages、channelCode、modelName。路由仅按启用、渠道、模型及 score 选择；Responses 自动提供 web_search 工具，其他策略返回正文和空引用，不从正文链接猜测引用。协议支持不保证实际搜索或引用。页面说明及资源版本已同步为 20261011-config3。全量 257 项测试、JS 语法及差异检查通过；本轮未重启服务、未做真实外部模型联调。下述旧记录中的单次搜索开关已失效，以本条为准。
- 删除 Service 的 getChannel 查询包装，直接调用 MyBatis-Plus getById；编辑、启停和详情在业务位置判断记录缺失，保存后直接 getById 回读。模型 Service、Business、Controller 定向测试通过，补充缺失记录回归，未重启运行服务。
- 模型管理调用链精简：保存命令转实体后不再拆出 apiKey 参数，Service 直接校验并保存该实体，删除二次实体创建及属性拷贝；仍清理系统字段、空密钥转换为 null 保留旧值。修正保存命令“无密钥”的失真注释。响应转换、保存回读数据库时间、启停只更新状态、协议连接快照均有实际职责，保留。新增回归验证空密钥、新密钥、系统字段及查询次数；全量 255 项测试通过，差异检查通过；本轮未做真实平台调用。
- 响应模型简化验证：最新全量 254 项测试通过，新增应用层回归覆盖保存、详情、列表、启停响应均无 apiKey，且 keyConfigured、搜索能力正常；git diff --check 通过。本次未重新验证运行中服务，新类型需重启服务加载。
- 注入风格统一：用户要求 Spring 依赖始终使用 @Resource，已写入 AGENTS.md；本轮模型 Controller、Business、策略路由均改为字段注入。协议策略注册改为 @PostConstruct，保持启动时拒绝重复协议，测试同步按字段注入与初始化生命周期执行。
- 协议重构完成：LLMProviderType 使用“描述、搜索能力”构造参数，包含 MOCK、OPENAI_COMPATIBLE、OPENAI_RESPONSES；策略通过 llmProviderType() 注册，重复协议在启动时拒绝。搜索能力由枚举派生，单次请求仍保留 webSearchEnabled；Responses 普通请求不声明搜索工具。页面协议选项从 /llm-channels/protocols 获取，导航版本 20261011-config2。
- 本地数据库已执行 008_llm_protocol_capabilities.sql：原支持搜索的兼容渠道转换为 OPENAI_RESPONSES，移除 web_search_supported。008 仅适用于原来有该字段的库；新库用更新后的 001 或 007 建表，不执行 008。最新全量 253 项通过，覆盖重复协议注册、搜索开关请求体；JS 语法与差异检查通过。8012 真实页面核验三个协议选项、无搜索能力复选框、切换协议后能力说明更新通过；外部模型联网调用待验证。
- 当前完成：全部管理页增加“基础配置”，包含模型配置、模型列表和迁入的浏览器登录管理。模型支持新增、编辑、查询、启停及 score 优先级，使用现有页面风格；诊断方案业务本轮未实现。
- 分层：LlmChannelController → LlmChannelBusiness → GeoLlmChannelService → GeoLlmChannelDao / GeoLlmChannelEntity，Service、DAO、实体名与 geo_llm_channel 表对应。已移除仅作中转的领域模型 LlmChannel，Service 直接使用实体，Business 转换为 LlmChannelResponse；密钥只在内部使用，响应仅公开 keyConfigured 及协议派生搜索能力。保存命令仍限制输入字段，编辑空密钥保留原值。增量 SQL 为 007_geo_llm_channel.sql，空库初始化同步到 001_init.sql。
- 数据库为模型配置唯一来源：本地 MySQL 已迁入 application.yml 中 6 个渠道及已有密钥，原默认 custom 启用、score=100，其余停用、score=0。YAML 模型段、配置绑定类与兼容读取已删除，无启动自动导入逻辑。新库需通过页面维护渠道。数据库密钥不返回列表或详情；编辑留空保留，填写新值替换。
- 路由：未指定时筛选已启用渠道，score 降序、ID 升序；指定渠道或模型增加筛选约束，搜索请求要求对应能力。条件不匹配或调用失败不自动更换模型。现有 query(String) 继续工作，完整请求支持 channelCode、modelName 和搜索开关；策略使用 LLMConnectionConfig 快照。
- 验证：JDK 17 下离线 clean test 全量 252 项通过；真实 MySQL 验证默认选择、模型筛选、指定模拟调用、缺失渠道拒绝。真实接口及浏览器完成新建、编辑、查询、启停、密钥留空保留和查询不回传密钥验证，临时验收渠道已清理。11 个管理页的菜单、链接与唯一选中状态检查通过。
- 运行：新版独立服务在 8012 验证，原 IDE 管理的 8010 进程未停止；需在 IDE 重启 8010 才能加载新接口。真实外部模型调用本轮未验证。用户原有头条、小红书 staged 改动保留，未提交。

## WorkBuddy / GPT 接手指令

- 架构与代码长期要求已整理到 `.claude/rules/project-conventions.md`；AGENTS.md、CLAUDE.md、项目记忆索引及通用架构规则均已添加入口和优先级说明。后续代码修改先读该文件，不能只根据本交接中的旧任务快照恢复已经简化的设计。本次仅更新项目文档，不修改代码或运行配置。
### 内容发布 Controller 合并（2026-10-11）

- 平台列表业务进一步合并：删除 PublicationPlatformLoginService，listConfiguredPlatforms 并入 ContentPublishBusiness；Controller 统一依赖该 Business，接口地址与响应保持不变。Business 依赖统一 @Resource。新增去重、枚举顺序及仅返回已接入平台的回归，clean test 全量 259 项及差异检查通过，src 无旧 Service 引用；运行服务未重启。
- ContentPublishController 改为 ContentPublishMgmtController，删除 PublicationPlatformController，将平台选项接口并入 GET /content-publish/platforms；预发布、发布记录列表和删除接口地址保持不变。依赖使用 @Resource。
- contents.js 与架构文档同步新路径，contents.html 脚本版本更新为 20261011-publish-mgmt1；src 无旧 Controller 或旧平台路径引用。平台列表契约测试并入 ContentPublishMgmtControllerTest，clean test 全量 258 项、JS 语法、差异检查通过。运行服务需重启以加载新接口，本轮未进行真实平台发布。

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
