# 业务 JSON 统一使用 Fastjson

核对日期：2026-10-14。范围已获用户确认：业务 JSON 解析与组装统一使用仓库已有的 `1.2.83_noneautotype`；Spring MVC 默认转换器及接口上的 Jackson 注解保留。没有升级依赖、修改数据库或提交代码。

## 实现与兼容边界

- 诊断快照使用 `JSONObject` / `JSONArray`，保留显式空值；当前渠道与历史渠道快照经相同数字解析规则再比较，避免整数类型及小数位数造成误判。
- `DiagnosisTaskSnapshots.read` 返回对象、数组或标量；JSON 字面量 `null` 返回 Java null。空输入明确拒绝，损坏 JSON 包装为快照读取异常。它不校验快照版本或字段完整性。
- 模型 HTTP 调用先取得字符串，再由 Fastjson 解析。请求协议、认证头和共用 HTTP 转换器未改变；文本、引用、用量及原始字段继续由协议模板解释。
- Fastjson 1.x 不识别 `LLMCitation` 的 record 访问器，直接序列化会产生空对象；成功结果落库时显式选择五个引用字段，保留位置空值，不改变公开引用结构。
- 问题生成、表单与扫码账号核验、东方财富草稿响应改用 Fastjson；缺失身份、非文本回答、无效引用、失败和截断回答仍按原业务规则处理。
- 制题异常分类同步识别 `JSONException`，不向页面返回模型原文。东方财富监听器仅记录异常类型，避免 JSON 解析异常携带平台原文。
- 登录客户端去掉不再需要的 ObjectMapper 依赖，浏览器组件改用项目约定的 `@Resource`。东方财富回归脚本同步移除旧构造参数。

## 验证结果

| 检查 | 结果 |
|---|---|
| Java 17 / IDEA Maven 离线 `compile` | 通过，146 个主源码文件 |
| 常规 Maven `test-compile` | 被原有 `WhatsAppDemo` 缺少 `it.auties.whatsapp.api` 依赖阻断 |
| 独立 javac 编译其余测试 | 通过；保留已有 varargs 和未检查转换警告 |
| 独立 JUnit | 302 项执行，301 项通过，1 项失败 |
| 失败项 | `LLMStrategyRouterTest.shouldRejectDisabledExactTaskChannel`：禁用渠道没有抛预期异常；生产路由和该测试均与 HEAD 一致，本次未修改 |
| 新增及迁移相关验证 | 历史空值/等值小数、密钥轮换、引用 record 落库、Chat 首候选与嵌套引用、非法回答、Responses 失败/截断/纯工具、制题尾随内容、Fastjson 错误分类均通过 |
| MVC JSON 契约 | 通过：快照为对象、引用为数组、位置空值与零用量保留，接口顶层空字段继续按原注解省略 |
| `.test/regtest` Java 脚本 | 独立编译通过，未运行真实平台操作 |
| 主源码残留扫描 | 无 ObjectMapper、JsonNode、JacksonException 使用；仅保留 MVC 注解 |
| `git diff --check` | 通过 |

独立测试入口为 `.test/FastjsonMigrationTestRunner.java`，测试类输出到 `target/fastjson-migration-tests`；依赖沿用 `target/diagnosis-task-test-classpath.txt`，另加本机 JUnit Platform Launcher 1.10.1。没有把独立运行表述为 Maven 全量测试通过。

未重启应用、未调用真实模型、未登录或发布真实平台内容；正在运行的实例需重新加载 Java 类后使用新实现。禁用精确渠道的既有行为与测试不一致，留待独立确认，不借 JSON 替换调整路由规则。

## 架构复核

快照及持久化规则仍在现有 domain/service，接口组装仍在 application，平台及协议解析仍在 infrastructure/client；没有增加业务流程、Controller、转换层或通用 JSON 包装器。`JSONObject` 作为 Map、`JSONArray` 作为 List 交给既有 MVC 转换器，对外不暴露库内部对象属性。数据库事务、会话锁、后台任务生命周期、浏览器资源所有权及认证来源未改变。

## 逐方法注释审计

下表对应本轮改动方法。每行均核对方法说明、实际阶段及关键决策，结果通过；单阶段不强行编号。测试方法按场景说明核对，未新增生产业务规则。

| 类#方法 | 实际阶段与分段 | 方法说明 | 决策 / 边界说明 | 结果 |
|---|---|---|---|---|
| DiagnosisTaskSnapshots#plan | 方案 → 项目 → 问题/渠道，三段编号 | 完整 | 显式字段、背景不拼入问题 | 通过 |
| DiagnosisTaskSnapshots#question | 问题字段映射，单阶段 | 完整 | 保留原题及分类 | 通过 |
| DiagnosisTaskSnapshots#channel | 渠道资料 → 策略协议 → 参数，三段编号 | 完整 | 密钥排除、策略缺失拒绝、保留空值 | 通过 |
| DiagnosisTaskSnapshots#read | 空输入校验 → JSON 解析，两段 | 完整 | JSON null、浮点比较和损坏失败 | 通过 |
| GeoDiagnosisTaskService#create | 校验/锁定 → 快照落库 → 采样初始化，三段编号 | 完整 | 保留原幂等、事务及密钥白名单 | 通过 |
| GeoDiagnosisTaskService#insertSamples | 预生成配置 → 组装采样 → 批量写入，三段编号与尾批说明 | 完整 | 保留采样唯一键与批次边界 | 通过 |
| GeoDiagnosisTaskService#prepareChannel | 回读双方配置 → 拒绝差异，两段 | 完整 | 相同数字规则、空值、轮换与安全日志 | 通过 |
| GeoDiagnosisTaskService#succeed | 引用映射 → 结果组装 → 条件写入，三段编号 | 完整 | record、显式空值、写入失败不重发 | 通过 |
| DiagnosisTaskBusiness#response | 回读与统计 → 渠道进度 → 详情响应，三段编号 | 完整 | 历史名称、未知状态拒绝 | 通过 |
| DiagnosisTaskBusiness#resultResponse | 回读快照 → 组装结果，两段 | 完整 | 数组/对象及 JSON 空值保留 | 通过 |
| QuestionGenerateService#generate | 校验 → 调用制题 → 解析校验，三段编号 | 完整 | 不落库、不降级空集合 | 通过 |
| QuestionGenerateService#buildPrompt | JSON 数据块提示词，单阶段 | 完整 | 项目资料只作制题背景 | 通过 |
| QuestionGenerateService#parseCandidates | 去围栏 → 解析数量 → 字段组装，三段编号 | 完整 | 完整 JSON、非文本拒绝 | 通过 |
| QuestionGenerateService#requiredText | 必需文本校验，单阶段 | 完整 | 不使用字符串强转接受容器 | 通过 |
| AbstractChatCompletionsApiStrategy#query | 配置 → HTTP → 解析，三段编号 | 完整 | 原始字符串、空响应失败 | 通过 |
| AbstractChatCompletionsApiStrategy#parseResponse | 首候选 → 引用 → 元数据，三段编号 | 完整 | 严格文本、结构错误失败、保留空字段 | 通过 |
| AbstractResponsesApiStrategy#query | 配置 → 请求 → HTTP → 解析，四段编号 | 完整 | 密钥与共用客户端边界 | 通过 |
| AbstractResponsesApiStrategy#parseResponse | 状态 → 文本/引用 → 完整性及元数据，三段编号 | 完整 | 失败/截断/纯工具不算成功 | 通过 |
| LlmCitationParser#collect | 数组 → 引用类型 → URL/资料，三段编号 | 完整 | 不猜测来源、按 URL 去重、整数位置 | 通过 |
| FormPlatformPlaywrightLoginClient#probeAccount | 请求 → HTTP → 平台解析，三段编号 | 完整 | HTTP 优先与未知状态、解析异常 | 通过 |
| FormPlatformPlaywrightLoginClient#parseYidianAccount | 业务码 → 媒体身份，两段 | 完整 | 成功码与媒体标识/名称 | 通过 |
| FormPlatformPlaywrightLoginClient#parseJianshuAccount | 受保护昵称核验，单阶段 | 完整 | 缺昵称为未知 | 通过 |
| FormPlatformPlaywrightLoginClient#parseZhihuAccount | 身份字段校验，单阶段 | 完整 | 标识与名称必须为文本 | 通过 |
| FormPlatformPlaywrightLoginClient#parseBaijiahaoAccount | 业务码 → 用户身份，两段 | 完整 | 未登录码优先、昵称回退 | 通过 |
| FormPlatformPlaywrightLoginClient#parseOschinaAccount | 业务状态 → 用户身份，两段 | 完整 | 显式成功与账号标识 | 通过 |
| FormPlatformPlaywrightLoginClient#parseImoocAccount | JSONP → 业务码 → 身份，三段编号 | 完整 | 包装限定、未知与未登录区分 | 通过 |
| FormPlatformPlaywrightLoginClient#parseNeteaseAccount | 业务码 → 账号身份，两段 | 完整 | 不能仅凭成功码保存会话 | 通过 |
| FormPlatformPlaywrightLoginClient#parseSohuAccount | 业务码 → 账号组 → 子账号，三段编号 | 完整 | 空数组及缺标识为未知 | 通过 |
| FormPlatformPlaywrightLoginClient#parseYuqueAccount | 页面数据 → 用户身份，两段 | 完整 | 默认头像不算登录、损坏为未知 | 通过 |
| FormPlatformPlaywrightLoginClient#probeWoshipmAccount | 写作页 → 个人资料 → 身份一致，三段编号 | 完整 | 标识必须一致 | 通过 |
| FormPlatformPlaywrightLoginClient#readAccountText | 标量提取，单阶段 | 完整 | 容器与缺失使用默认值 | 通过 |
| FormPlatformPlaywrightLoginClient#readAccountNumber | 数字提取/文本解析，同一目的分段 | 完整 | 非法码不补为成功零值 | 通过 |
| FormPlatformPlaywrightLoginClient#readAccountBoolean | 成功标志提取，单阶段 | 完整 | 缺失不推定成功 | 通过 |
| QrPlatformPlaywrightLoginClient#probeAccount | 平台请求 → HTTP → 解析，三段编号 | 完整 | 动态签名、认证拒绝与未知 | 通过 |
| QrPlatformPlaywrightLoginClient#probeCsdnAccount | 页面请求 → HTTP → 身份，三段编号 | 完整 | 页面标题/Cookie 不是成功证据 | 通过 |
| QrPlatformPlaywrightLoginClient#probeEastmoneyAccount | 页面响应 → 作者解析，两段 | 完整 | 页面失败或解析异常为未知 | 通过 |
| QrPlatformPlaywrightLoginClient#parseEastmoneyAccount | 业务码 → 作者身份，两段 | 完整 | 标识、昵称均必需 | 通过 |
| QrPlatformPlaywrightLoginClient#parseAccountResponse | 空响应 → 分平台判定，两段 | 完整 | 各平台独立业务码 | 通过 |
| QrPlatformPlaywrightLoginClient#parseZhihuAccount | 文本身份校验，单阶段 | 完整 | 不强转数字与容器 | 通过 |
| QrPlatformPlaywrightLoginClient#parseWeiboAccountHtml | 页面配置 → 账号身份，两段 | 完整 | 登录跳转页不算身份 | 通过 |
| QrPlatformPlaywrightLoginClient#parseJuejinAccount | 业务状态 → 账号身份，两段 | 完整 | 显式空 data 与缺失字段区分 | 通过 |
| QrPlatformPlaywrightLoginClient#parseBilibiliAccount | 业务/登录状态 → 昵称展示，两段 | 完整 | 认证码优先、正数 mid 必需 | 通过 |
| QrPlatformPlaywrightLoginClient#parseToutiaoAccount | 业务码 → 用户身份，两段 | 完整 | 成功码仍需用户标识 | 通过 |
| QrPlatformPlaywrightLoginClient#parseXiaohongshuAccount | 成功标志 → 用户身份，两段 | 完整 | 明确失效码才判未登录 | 通过 |
| QrPlatformPlaywrightLoginClient#parseDouyinAccount | 业务码 → 账号路径兼容，两段 | 完整 | 保留两种身份路径 | 通过 |
| QrPlatformPlaywrightLoginClient#parseCsdnAccount | 业务码 → 用户身份，两段 | 完整 | 用户名必需、昵称作展示 | 通过 |
| QrPlatformPlaywrightLoginClient#parseSmzdmAccount | 数字身份状态 → 昵称展示，两段 | 完整 | 零为未登录、负数为未知 | 通过 |
| QrPlatformPlaywrightLoginClient#readAccountText | 标量提取，单阶段 | 完整 | 缺失与容器使用默认值 | 通过 |
| QrPlatformPlaywrightLoginClient#readAccountNumber | 数字提取/文本解析，同一目的分段 | 完整 | 非法码不补为成功零值 | 通过 |
| QrPlatformPlaywrightLoginClient#readAccountBoolean | 成功标志提取，单阶段 | 完整 | 缺失不推定成功 | 通过 |
| EastmoneyBrowserClient#captureDraftId | 网关筛选 → 内层 JSON → 草稿 ID，三段编号 | 完整 | 文本限定、监听失败隔离、日志不含原文 | 通过 |
| DiagnosisTaskWorker#execute | 锁连接 → 领取 → 调用 → 保存，原编号保留 | 完整 | 独立问题、失败不重发及锁边界 | 通过 |
| MgmtDiagnosisPlanController#generationFailed | 原因分类 → 安全响应，两段 | 完整 | Fastjson 分类、不回传模型原文 | 通过 |

复核未扩展既有渠道启停语义，未移除 MVC 的密钥隐藏注解。
