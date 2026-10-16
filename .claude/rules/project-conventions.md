# colorful-geo-mgmt 架构与代码约定

本文件记录与用户确认的长期工程要求。修改本项目代码前必须阅读；与通用模板规则冲突时，以本文件及用户最新明确要求为准。临时进度和验证结果写入 `.doc/HANDOFF.md`。

## 先判断设计是否必要

- 用户提出方案时，先检查已有实现、上下游调用和数据来源，给出独立判断。重复配置、职责冲突或不必要的复杂度应主动指出，不默认顺着提议增加代码。
- 每个类、方法、字段必须承担具体职责。不能只因“DDD 应该有这一层”而新增类或转换。
- 同一个事实只维护一处；能从已有类型推导的信息，不再增加数据库字段重复维护。
- 空占位类和只有调用转发的中间层应评估删除或合并；规划留在文档，实际开发时再建立实现。
- 类小不等于无价值：请求与响应边界、协议策略、真实业务编排应根据职责判断，不能机械合并。

## 分层与数据流

本项目采用现有目录分层：

```text
presentation/controller：接口接收、响应及 HTTP 错误映射
application/*Business：应用编排、分页输入和接口模型转换
domain/service：业务规则、校验及现有持久化服务
infrastructure/repository：DAO 与数据库实体
infrastructure/client：外部协议、平台及浏览器适配
```

- 简单配置和基础档案 CRUD，Service 直接使用数据库实体，不额外建立只有同名字段的领域模型。
- 数据流采用 `Request → Entity → Response`：应用层接收请求并构造实体，Service 校验和保存实体，应用层构造响应。
- 只有存在独立领域行为、不变量或生命周期时，才引入领域模型；不要提前为未来可能的需求建立抽象。
- 输入限制、密钥隔离、协议适配有实际价值，不能在简化时删掉。
- 不使用 `BeanUtils.copyProperties` 等按属性名隐式复制；字段显式赋值，不为转换新增无必要的框架或中间对象。

## 命名与依赖注入

- 表名采用模块前缀，实体、DAO、持久化 Service 与完整表名一致，例如：

| 表名 | 实体 | DAO | Service |
|---|---|---|---|
| `geo_llm_channel` | `GeoLlmChannelEntity` | `GeoLlmChannelDao` | `GeoLlmChannelService` |
| `geo_project_info` | `GeoProjectInfoEntity` | `GeoProjectInfoDao` | `GeoProjectInfoService` |

- 接口输入命名 `XxxRequest`，接口输出命名 `XxxResponse`。只有独立于接口的业务命令才使用 `XxxCommand`，不为命名增加 `Request → Command` 转换。
- 合并或修改职责后同步类名、方法名和注释，不保留已经失真的 `LoginService` 等名称。
- Spring 依赖始终使用 `jakarta.annotation.Resource` 字段注入；不使用构造器注入或 `@RequiredArgsConstructor` 注入依赖。注入后的初始化使用 `@PostConstruct`。
- Lombok 可用于数据模型及日志；枚举构造参数使用 `@RequiredArgsConstructor` 不属于 Spring 依赖注入。
- 使用 `channel`、`entity`、`response` 等能表达职责的变量名，避免 `c`、`p` 等含糊缩写。

## 方法与可读性

- 已有 MyBatis-Plus 能力直接使用，例如 `getById`；不再增加仅查询后返回的包装方法。记录缺失判断放在需要该业务语义的位置。
- 避免重复查询和实体二次复制。保存后为取得数据库生成的 ID、时间而回读，有明确用途，可保留。
- 状态更新只修改目标字段，避免把旧实体全量回写覆盖其他配置。
- 避免无意义嵌套、取反的复杂三元表达式和挤在一行的查询／转换／计数；按业务步骤拆成清晰语句，不为减少行数牺牲可读性。
- 日志记录实际选中渠道及必要结果，避免入口和下层重复记录同一调用，不打印问题原文、回答原文或凭据。
- 注释解释职责、规则及必要边界，与最终实现一致；修改后删除过时的能力描述和兼容说明。

## 已确认的大模型设计

- 配置唯一来源为数据库 `geo_llm_channel`，不恢复 YAML 模型配置、自动导入或默认回退。
- 默认筛选已启用渠道，按 `score` 降序、ID 升序选择；指定渠道或模型时增加对应约束。无匹配或调用失败明确返回，不自动切换模型。
- 渠道直接保存 strategy_bean_name，用 Spring 注入的 Map<String, LLMStrategy> 按 Bean 名获取实例。不再维护额外策略编码、自建注册表或 strategyType/strategyCode/strategyName 包装方法；缺失 Bean 保存与调用时明确拒绝，不反射、不降级。
- 页面使用数据库渠道名称和模型名称，策略选项直接展示 Spring Bean 名及协议，不额外维护策略中文名称枚举。厂商策略通过 `apiKeyConsoleUrl()` 声明 API Key 获取页，经 /llm-channels/strategies 返回给配置页展示；通用策略与 Mock 返回 null，前端不另维护地址表。协议由协议父类或 Mock 声明，不存入数据库或重复放入策略目录；枚举值、字段和业务方法须有简洁注释。重命名 Bean 时同步迁移数据库配置。
- Chat Completions、Responses 抽象父类复用协议交互与引用解析，子策略处理厂商参数。protocol_type 不独立持久化，响应从实例推导；provider_code 只作供应商资料。页面选项来自 /llm-channels/strategies 的实际 Bean Map，不开放未实现策略。
- 路由一次查询选取 GeoLlmChannelEntity 后直接交给策略，不建立同字段的连接配置副本或重复传递密钥。未知 Bean 和远程空密钥在调用前拒绝；实体的密钥不得参与日志、toString 或接口响应。只有协议调用和异常边界承担实际职责的层保留，纯转发和已停用 SDK 代码及时删除。
- LLMProtocolType 只表示 MOCK、OPENAI_CHAT_COMPLETIONS、OPENAI_RESPONSES 交互格式。引用始终按响应的结构化字段解析，不根据搜索标志过滤，也不从正文 URL 或搜索召回来源猜测。搜索参数按供应商处理，不把普通兼容供应商自动标为支持联网。
- 引用只来自接口结构化数据，不把正文链接当成实际引用，不虚构引用。
- 不返回 url_citation 的厂商由子策略重写 `collectStrategyCitations`，把接口结构化返回的来源网址补为引用：DeepSeek 取 `web_search_call` 的 `open_page`（去掉 `ws_call_id` 标识），千问、智谱取 `web_search_call.action.sources` 中 `type=url` 的网址（共用 `LlmCitationParser.collectSearchSources`）；文心（千帆智能搜索 Chat）取响应顶层 `references` 中 `type=web` 的网址与标题，正文 `^[n]^` 角标不解析。Chat 与 Responses 父类各提供同名默认空实现的补充入口。标题、摘要、位置留空且不抓取补全；检索词不作为引用；文本注解已有同一网址时保留注解资料。标准 url_citation（豆包、OpenAI）留在父类统一解析，不下沉到子策略。
- `LLMQueryRequest` 仅含 `prompt`、`channelCode`、`modelName`，每次独立调用，无角色枚举、消息列表或历史上下文。重复采样通过多次独立调用完成。
- 密钥仅供服务端使用，响应只返回 `keyConfigured`；编辑空密钥保留原值，输入新值才替换。
- 配置页测试连接（`POST /llm-channels/test`）用表单当前配置经路由器真实调用一次，不写库；校验与密钥保留规则复用保存逻辑。调用失败由 `infrastructure/client/llm/LLMCallFailureClassifier` 按有序规则表翻译为 `LLMCallFailureType`，以结果返回而非 HTTP 错误；厂商报错原文截断后只返回给配置页，不进日志。新增失败识别只在规则表加行并注明顺序原因，不在业务层散落状态码判断。

## 修改后的检查

- 检查完整生产者和消费者，包括源码、测试、前端、SQL、资源版本及有效文档引用；不只修改用户指出的一行。
- 重命名表或类时同步关联引用，已有表迁移保留数据，避免创建一个同名空表替代原数据。
- 保留用户未提交改动，不自动提交、重置、清理或批量格式化。
- 测试覆盖实际业务边界，不为重复字段赋值写机械测试；按改动运行相关验证，区分代码实现、测试通过和真实平台联调。
- 长期规则写入本文件，当前完成情况、风险和下一步写入 `.doc/HANDOFF.md`；明确服务是否重启，不把源码修改当成运行实例已更新。
