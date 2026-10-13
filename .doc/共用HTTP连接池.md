# 共用 HTTP 连接池

## 实现

`infrastructure/config/RestTemplateConfig` 提前注册 Apache HttpClient 5 客户端和 RestTemplate，两个大模型协议模板通过 `@Resource(name = "llmRestTemplateWithTimeout")` 注入同一实例。删除原来绑定渠道实体的 LlmHttpClientFactory，不新增模型路由层。

- `llmHttpClient`：提前创建模型专用连接池客户端，Spring 通过 destroyMethod=close 释放资源。
- `llmRestTemplateWithTimeout`：具名 Bean，两个协议模板直接注入；已删除通用默认 Bean、动态 getRestTemplate 方法和双池创建逻辑。
- `application.yml` 的 http.llm.timeout-seconds=60、http.llm.ssl-verify=true 统一控制模型 HTTP 超时和证书校验，不依赖渠道实体。数据库渠道 timeoutSeconds/sslVerify 字段仍保留，但不再影响 HTTP 请求，页面字段尚未清理；后续应删除或改为只读说明，避免误导。
- 连接上限 100、单目标上限 20；一个具名客户端对应一个池。只有明确将统一配置设为 false 才关闭证书链和主机名校验，接口没有总体执行截止时间。
- 自动回收过期及闲置 30 秒的连接，Spring 停止时关闭客户端及回收线程。不手工创建调度器，不加入负载均衡。
- 禁用自动重试、重定向和 Cookie 管理；认证头只存在于业务请求，不存入共用客户端。
- 模型策略仍负责地址、认证头、请求体及 JSON 解析；本次不修改搜索参数、引用规则或数据库配置。

## 验证

Java 17 主代码构建通过。常规 Maven 测试编译被原有 WhatsAppDemo 缺失依赖阻断，使用独立 javac/JUnit Launcher 执行相关测试：25 项通过。

验证具名 Bean 单例及底层客户端注入、实际 TCP 连接复用、统一响应超时及超时不重试、两种协议通过 Spring 注入后经本地 HTTP 回读正文与引用。

本轮未进行真实外部模型或 HTTPS 证书握手联调；运行服务未重启，无数据库迁移。用户原有未提交改动保留。

## 接口依据

依据 [Spring 6.1.1 HttpComponentsClientHttpRequestFactory 文档](https://docs.spring.io/spring-framework/docs/6.1.1/javadoc-api/org/springframework/http/client/HttpComponentsClientHttpRequestFactory.html)，使用 HttpClient 5 和请求级 HttpContext；示例中的 HttpClient 4 导入不能直接用于当前 Spring 6 项目。

## 后续

其他模块如需不同 HTTP 策略，提前注册各自具名 Bean 并按名称注入，不在调用处创建。真实模型和 TLS 行为仍需后续联调确认。
