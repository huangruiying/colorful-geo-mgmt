# 命名规范

# 1. 命名目标

命名的核心目标：

- 见名知意
- 降低理解成本
- 降低沟通成本
- 降低误用风险
- 保持团队风格统一

禁止为了简短而牺牲语义。

---

# 2. 核心原则

## 2.1 优先表达业务语义

命名应优先说明：

- 业务对象
- 业务动作
- 数据含义
- 状态语义

推荐：

```python
create_order()
paid_order_list
user_status
```

禁止：

```python
data
info
obj
```

---

## 2.2 统一风格

同一项目中：

- 相同概念必须尽量使用同一单词
- 相同层级必须尽量保持同一命名模式
- 禁止同一概念多种叫法混用

例如：

- 不要同时使用 userId / user_id / uid 表达同一含义
- 不要同时使用 create / save / add 表达同一业务动作

### 2.2.1 相同机制、相同字段名

多个组件/模块共用同一底层机制时，触发该机制的配置字段名必须完全一致，禁止为同一机制另起新名。

**反例**：`ListenResponseTargetVal` 用 `early_exit_response` / `early_exit_push_info` 触发提前终止；新写的 `ForEachCollect[single_target_mode]` 也触发同一套机制，却用了 `target_not_found_response` / `target_not_found_push`——两套名字指向同一行为，违反本规则。

**正例**：`ForEachCollect[single_target_mode]` 统一沿用 `early_exit_response` / `early_exit_push_info`，与所有其他触发 early_exit 的组件保持一致。

**判断方法**：新参数最终写入的是同一个 ctx 字段（如 `ctx.early_exit_response`）？是 → 必须用和已有组件相同的配置字段名。

---

## 2.3 长度适中

命名应：

- 足够清楚
- 不必刻意最短
- 不必无意义过长

推荐：

```python
payment_retry_count
```

不推荐：

```python
cnt
number_of_payment_retry_operations_for_current_order
```

---

## 2.4 禁止无依据缩写

允许使用行业通用缩写，例如：

- id
- url
- http
- sql
- dto
- vo

禁止使用个人习惯缩写，例如：

- usr
- cfgInfo
- tmpData
- objVal

---

# 3. 不同对象命名规范

## 3.1 类名 / 结构体名 / 实体名

推荐：

- 使用名词或名词短语
- 明确表达业务对象
- 按职责决定后缀

例如：

```java
OrderService
UserRepository
PaymentRequest
RiskRuleEngine
```

禁止：

```java
DataManager
CommonProcessor
BaseHandler
TmpService
```

---

## 3.2 方法名

方法名 = **动词 + 业务对象**，动词表达动作，业务对象说明操作目标。

### 固定动词表

常用动词语义已约定，遇到以下场景必须使用对应动词，保证全工程一致。

| 分类 | 动词 | 语义 | 典型示例 |
|---|---|---|---|
| 构造 | `build` | 以主对象为核心构造 DB / 领域对象（来源单一） | `buildWithdrawalLog` |
| 构造 | `assemble` | 多来源拼装外部请求参数（来源复杂） | `assembleAdaWithdrawalRequestParam` |
| 构造 | `convert` | A 对象 → B 对象，结构变语义不变 | `convertToSettlement` |
| 校验/计算 | `validate` | 纯校验，失败抛 BizException，无返回值 | `validateWithdrawalRequest` |
| 校验/计算 | `calculate` | 纯计算，无副作用，返回计算结果 | `calculateFee` |
| 校验/计算 | `confirm` | 校验 + 计算混合，返回最终确定值 | `confirmWithdrawalAmount` |
| 查询 | `get` | 按主键 / 唯一键查单个，期望存在（不存在抛异常） | `getByOrderNo` |
| 查询 | `find` | 按条件查单个，允许不存在（返回 null / Optional） | `findByPhone` |
| 查询 | `list` | 按条件查多个 | `listByMchId` |
| 查询 | `load` | 加载配置 / 上下文 / 批处理数据 | `loadAdaPayProperties` |
| 持久化 | `create` | 新增记录 | `createWithdrawalOrder` |
| 持久化 | `update` | 更新已有记录 | `updateWithdrawalStatus` |
| 持久化 | `delete` | 删除记录 | `deleteExpiredRecord` |
| 通信 | `notify` | 推送通知给明确接收方（短信、回调、Push） | `notifyChannelResult` |
| 通信 | `publish` | 发布事件到消息总线，不关心消费方 | `publishWithdrawalEvent` |
| 编排 | `process` | 批量 / 流水线编排，无更具体业务动词可用 | `processBatch` |
| 编排 | `handle` | 外部事件 / 触发器入口（MQ 消费、Webhook） | `handlePaymentCallback` |
| 编排 | `execute` | 定时任务 / 异步 Job 执行入口 | `executeSettlementJob` |

表中未覆盖的场景，使用最贴近业务语义的动词，遵循"动词 + 业务对象"原则即可。

### 编排类动词使用条件

`process` / `handle` / `execute` **有条件允许**，核心判断标准：**有更具体的业务动词时，禁止使用**。

| 动词 | 允许 | 禁止 |
|---|---|---|
| `process` | 批量/流水线编排，确实无业务动词可替代 | 替代具体业务动词，如用 `processOrder` 代替 `createOrder` |
| `handle` | MQ 消费、Webhook、外部回调等事件入口 | service 内部方法的懒命名 |
| `execute` | 定时任务、异步 Job 的执行入口 | 同上 |

### 永远禁止

```java
doData()    // 无任何语义
exec()      // 过于模糊
process()   // 无业务对象时禁止，如 process()、doProcess()
handle()    // 无业务对象时禁止，如 handle()、doHandle()
```

**例外：以下两种情况不受此限制**

1. **框架生命周期 / SDK / ORM 约定方法**（Spring、MyBatis 等框架强制命名）
2. **自定义框架 / 抽象类 / 通用组件接口**，且类型名本身已提供足够语义

```java
// ✅ 类型名 DataProcessor 是语义锚点，process() 是刻意通用的扩展点
interface DataProcessor {
    void process(DataContext ctx);
}

// ✅ 模板方法，BaseFlowStep 提供语义，execute() 供子类覆盖
abstract class BaseFlowStep {
    abstract void execute(FlowContext ctx);
}

// ❌ 业务 Service 里类型名不是语义锚点，必须用具体业务动词
class OrderService {
    void process(OrderRequest req) { ... }  // 应改为 createOrder()
}
```

**判断标准**：类型名本身是否已提供足够语义？是 → 通用动词可接受；否 → 必须用具体业务动词。

---

## 3.3 变量名

变量名应：

- 表达真实含义
- 避免过度依赖上下文猜测
- 避免使用无语义占位词

推荐：

```python
active_user_list
retry_count
payment_result
```

禁止：

```python
list1
tmp
res
data
```

---

## 3.4 常量名

常量必须：

- 明确语义
- 避免魔法值
- 尽量体现单位或边界

推荐：

```java
MAX_RETRY_COUNT
DEFAULT_TIMEOUT_MILLIS
ORDER_EXPIRE_MINUTES
```

不推荐：

```java
TIMEOUT
NUM
VALUE
```

---

## 3.5 布尔变量名

布尔命名建议使用：

- is
- has
- can
- should
- enable

推荐：

```java
isPaid
hasPermission
canRetry
shouldRefreshCache
```

不推荐：

```java
paidFlag
permissionState
retry
cache
```

---

## 3.6 集合变量名

集合命名应尽量体现：

- 元素是什么
- 集合类型或业务范围

推荐：

```java
userList
activeOrders
roleSet
configMap
```

不推荐：

```java
dataList
allInfo
map1
setTmp
```

---

# 4. 后缀使用规范

以下后缀应仅在语义明确时使用：

- Request / Response
- DTO / VO
- Entity
- Service / Repository / Controller
- Manager / Factory / Builder

原则：

- 后缀必须体现职责
- 禁止仅为了显得规范而乱加后缀
- 同名不同职责对象必须靠后缀区分

例如：

```java
CreateOrderRequest
OrderEntity
OrderRepository
```

---

# 5. 禁止命名场景

## 5.1 禁止使用无业务语义的通用词

例如：

- data
- info
- item
- thing
- object
- handler
- processor

当这些词无法单独表达业务含义时，禁止直接使用：

- data
- info
- item
- thing
- object

## 5.2 禁止使用序号命名

例如：

```python
user1
user2
list3
```

序号只能表示顺序，不能表达语义。

---

## 5.3 禁止中英混杂或拼音命名

禁止：

```python
userMingdan
dingdanStatus
```

除非项目已有明确历史约定，否则应统一使用英文命名。

---

# 6. Review 检查项

Code Review 时必须检查：

- 命名是否见名知意
- 是否存在无意义缩写
- 是否存在同义词混用
- 是否存在职责误导
- 布尔命名是否像布尔含义
- 集合命名是否体现元素语义

---

# 7. 最终原则

优秀命名应做到：

- 看名字就知道是什么
- 看名字就知道在做什么
- 看名字就知道边界大致在哪里

命名应帮助理解代码。

而不是迫使读代码的人反复猜测。
