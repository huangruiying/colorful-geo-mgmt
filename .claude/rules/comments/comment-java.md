## Java 注释硬性规则

以下规则优先级高于 `code-scope.md` 中"默认不写注释"和"禁止无意义多行注释块"的约束：

1. 新增 Java 类时，类注释必须说明职责、核心入口、边界。
2. 新增或修改业务方法时，public/protected 方法必须有方法注释。
3. private 方法如果包含业务规则、路由适配、状态变更、落库、外部调用、历史兼容，也必须有方法注释。
4. 任意业务方法只要包含 2 个及以上业务阶段，无论 public/private，都必须添加方法内分段注释；包含 3 个及以上业务阶段时，必须使用 `// 1.`、`// 2.`、`// 3.` 编号标出主流程。
5. AI 必须主动识别方法内部的业务阶段与职责边界，不能只按分支数量或方法长度判断是否需要注释；识别标准见“业务阶段与边界识别”。
6. 外部响应解析、状态机 / 状态映射、错误码优先级、资金或订单结果判定、请求响应一致性校验等业务决策逻辑，必须按“决策组”添加方法内注释；该要求不受业务阶段数量限制，方法 Javadoc 不能替代方法内注释。
7. 路由兼容、历史兼容、兜底逻辑必须加代码块注释，说明为什么需要该兼容。
8. AI 生成或修改 Java 代码后，必须逐个检查本次新增或修改的方法；存在未满足类注释、方法注释、业务分段注释、主流程步骤注释、业务决策注释的 Java 代码时，禁止交付最终结果。

---

## 文件头部注释

用于说明文件职责、核心流程和不负责的边界。

```java
/**
 * <pre>
 * 订单风控主流程
 *
 * 负责：
 * 1. 风险识别
 * 2. 黑名单校验
 * 3. 支付风控拦截
 *
 * 不负责：
 * - 支付扣款
 * - 库存处理
 * </pre>
 */
```

---

## 类注释

类注释必须说明：类的职责、核心入口、边界。不要只翻译类名。

```java
/**
 * <pre>
 * 风控规则执行器
 *
 * 负责：
 * - 按订单上下文编排黑名单、设备、支付规则
 * - 统一返回风控命中结果
 *
 * 核心入口：
 * - audit(...)
 *
 * 不负责：
 * - 支付扣款
 * - 库存处理
 * </pre>
 */
public class RiskRuleExecutor {
}
```

---

## 方法注释

业务方法注释必须说明功能、作用、参数、返回。重点解释"为什么需要这个方法"，不是复述方法名。

```java
/**
 * <pre>
 * 功能：返回 session 文件的 mtime。
 * 作用：用 session 文件的 mtime 作为账号标识。
 * - 切换账号后 session 文件会被重写，mtime 变化后 cache key 随之变化
 * - 不读文件内容，避免每次算 key 都做 IO 和哈希
 * </pre>
 *
 * @param payload 服务端下发的参数
 * @return session 标识
 *         nochannel 没有 channel 标识
 *         nosession 没有 session 文件
 *         xxxxxx    真实有效的 mtime
 */
private String getSessionMtime(Map<String, Object> payload) {
    return "xxx";
}
```

---

## 业务代码注释

### 业务阶段与边界识别

不能用代码行数、`if` 数量或是否为主入口判断一个方法是否需要分段注释。方法内出现下列任一变化，通常表示进入了新的业务阶段：

- 处理目的发生变化，例如从参数校验转为配置查询
- 操作对象发生变化，例如从路由配置转为完整通道配置
- 数据来源发生变化，例如从入参转为数据库、缓存或外部接口
- 失败语义发生变化，例如从“请求不合法”转为“业务数据不存在”
- 动作性质发生变化，例如从查询转为转换、组装、状态判定、落库或外部调用
- 输出目的发生变化，例如从构造基础对象转为补齐下游调用所需上下文

连续代码虽然没有复杂分支，但承担不同业务目的时，仍应拆成不同阶段。注释应概括该阶段的业务目的或边界，不要逐行翻译代码。

```java
// 1. 校验路由配置，确保具备定位完整通道配置的父级商户号
validateRouteConfig(routeConfig);

// 2. 根据父级商户号加载完整通道配置，缺失时阻断查询
PayChannelConfig channelConfig = loadChannelConfig(routeConfig);

// 3. 转换并补齐本次交易上下文，供后续通道请求直接使用
return buildTradeConfig(routeConfig, channelConfig);
```

### 主流程步骤注释

业务方法包含 2 个业务阶段时，必须使用普通分段注释标明边界；包含 3 个及以上连续业务阶段时，必须用 `// 1.`、`// 2.` 编号注释标出。

```java
@Override
public Boolean audit(AuditRequest request) {
    // 1. 定位待审核单据
    AuditRecord record = auditService.getById(request.getId());

    // 2. 校验审核请求和单据状态
    checkAuditRequest(request, record);

    // 3. 调整审核状态并落库
    updateAuditStatus(request, record);

    // 4. 通知渠道审核结果
    notifyAuditResult(record);

    return true;
}
```

禁止：主流程方法只有方法 Javadoc，没有步骤编号注释。

### 业务决策注释

以下逻辑即使集中在单个业务阶段、没有密集分支，也必须按决策组添加方法内注释：

- 外部接口公共响应与业务响应的分层判断
- 错误码、业务状态的判断顺序与优先级
- 状态机、状态映射以及未知状态处理
- 资金、订单等核心结果的成功、处理中、失败判定
- 请求流水、商户号等请求响应一致性校验

注释应说明该组判断的业务语义、优先级或兜底原因，不要逐个翻译 `if` 条件。表达同一业务结论的连续条件可以共用一条注释。

```java
// 公共响应只代表网关受理成功，业务结果仍需继续读取业务响应
if (!isGatewaySuccess(response)) {
    throw new ServiceException("网关响应失败");
}

// 处理中错误码优先于业务状态，避免把尚未终态的请求误判为失败
if (PROCESSING_CODE.equals(response.getRetCode())) {
    return Status.PROCESSING;
}
```

禁止：只在方法 Javadoc 中笼统写“解析响应”，方法内没有解释各决策组的业务含义。

### 兼容和兜底注释

路由兼容、历史兼容、兜底逻辑必须解释为什么存在。

```java
// 兼容旧版渠道未传 deviceId 的历史数据，避免灰度期间路由规则全部失配
if (StringUtils.isBlank(request.getDeviceId())) {
    request.setDeviceId("unknown");
}
```

### 外部调用示例

外部调用代码不需要大段注释，日志需要说明业务动作和关键响应。

```java
@Retryable(include = ServiceException.class, backoff = @Backoff(delay = 1000, multiplier = 2.0))
public void doNotifyRetry(NotifyRequest request) {
    log.info("AuditBiz_doNotifyRetry_通知渠道审核结果:{}", JSON.toJSONString(request));
    Result<Boolean> response = channelClient.notifyAudit(request);
    log.info("AuditBiz_doNotifyRetry_渠道响应:{}", JSON.toJSONString(response));
}
```

---

## 禁止示例

```java
// 获取记录
AuditRecord record = auditService.getById(id);

// 判断是否为空
if (record == null) {
    ...
}
```

这类注释只翻译代码字面含义，应改成业务原因。
