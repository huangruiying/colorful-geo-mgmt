# 异常处理规范

---

## 1. 异常分类体系（4层）

两个正交维度：
- **业务视角**：预期内（正常流转）/ 预期外（真正出错）
- **系统视角**：可恢复 / 不可恢复

| 类型 | 典型场景 | 日志 | HTTP | 告警 |
|---|---|---|---|---|
| `BizException` | 余额不足、订单状态不对、用户名已存在 | WARN/INFO | 200 + 业务码 | ❌ |
| `ParamException` | 手机号格式错误、必填字段缺失 | WARN | 400 | ❌ |
| `SysException` | DB 写入失败、内部服务调用超时 | ERROR + 堆栈 | 500 | ✅ |
| `ThirdPartyException` | 支付宝超时、短信服务不可用 | ERROR + 堆栈 | 500/降级 | ✅（区分外部） |

> 小项目：`BizException` + `SysException` 两层足够  
> 大项目：完整四层 + 异常码 + traceId 透传

---

## 2. 全局异常处理结构

```
GlobalExceptionHandler
  ├── BizException        → 200 + {code, message}，WARN
  ├── ParamException      → 400 + 参数提示，WARN
  ├── SysException        → 500 + 通用提示，ERROR + 告警
  ├── ThirdPartyException → 500/降级，ERROR + 外部告警
  └── Throwable（兜底）   → 500，FATAL + 紧急告警
```

---

## 3. 异常码规范

格式：`[MODULE]_[序号]`，禁止纯数字

```java
public enum BizErrorCode {
	USER_NOT_FOUND("USER_001", "用户不存在"),
	BALANCE_INSUFFICIENT("PAY_001", "余额不足"),
	ORDER_STATUS_INVALID("ORDER_001", "订单状态异常");
}
```

---

## 4. 核心规则

### 4.1 禁止吞异常

```python
# ❌
try:
    run()
except Exception:
    pass

# ✅ 能恢复才 catch，否则向上抛
try:
    run()
except SpecificError as e:
    fallback_or_raise(e)
```

### 4.2 BizException 两种抛出场景

**场景A：校验拦截直接 throw（无 cause）**
```java
// ✅ message 写清楚业务拒绝原因，不需要 cause
if (balance.compareTo(retainedBalance) <= 0) {
		throw new BizException(400, "可提现金额不足留存金额");
}
```

**场景B：catch 块里转换已有异常（必须保留 cause）**
```java
// ✅ message 自己写干净的业务描述，cause 保留原始堆栈，禁止拼接 e.getMessage()
} catch (InsufficientFundsException e) {
		throw new BizException("余额不足", e);   // ✅
// throw new BizException("余额不足: " + e.getMessage(), e);  // ❌ message 混入技术细节
}
```

### 4.3 非 HTTP 场景：service 方法内 catch BizException

BizException.message 是手动写入的有意义业务描述，catch 时根据方法返回类型生成对应 return：

```java
// 返回 Pair<Boolean, String>
} catch (BizException e) {
		return new Pair<>(false, e.getMessage());
		}

// 返回 Result<T>
		} catch (BizException e) {
		return Result.fail(e.getMessage());
		}

// 返回 boolean
		} catch (BizException e) {
		log.warn("withdrawal rejected, reason={}", e.getMessage());
		return false;
		}
```

> LLM 生成 catch 代码时：识别方法返回类型 → 自动用 `e.getMessage()` 填充失败返回值。

### 4.4 捕获具体类型，不捕获 Exception

```java
// ❌
catch (Exception e) { throw new RuntimeException("failed"); }

// ✅ BizException 先捕获，Exception 作为最终兜底
		} catch (BizException e) {
		return new Pair<>(false, e.getMessage());   // 业务拒绝，不打 ERROR
		} catch (Exception e) {
		log.error("withdrawal failed", e);          // 系统异常，打 ERROR
}
```

### 4.5 同一异常不重复打印

谁真正处理，谁记录；禁止每层 catch 都打印一次。

### 4.6 非核心流程异常隔离

写代码或抽取方法时，先按 `refactor.md` 的“核心流程与非核心流程识别”判断职责归属。

非核心流程不得阻断主流程；非核心流程抽成方法时，方法内部自行 `try-catch`，失败后记录 `warn` 日志并吞掉异常。

---

## 5. 重试 / 降级规则

**可重试**：瞬时网络抖动、第三方超时、临时资源竞争  
**不可重试**：参数错误、数据格式错误、明确业务拒绝

降级必须显式：说明为什么降级、降到什么行为、对结果的影响。  
禁止悄悄失败后继续返回"看似正常"的数据。

---

## 6. 幂等写入规则

适用于：批量落库、消息重复消费、回调重复到达

- Repository 层提供明确幂等写入方法，如 `bulk_create_ignore_existing`
- 返回必须区分 `inserted` / `skipped`，不能让调用方误以为全部新建成功
- 只忽略"已存在"冲突，其他 DB 异常必须继续抛出
- 禁止在 service/controller 层散落重复查重逻辑

---

## 7. 分层职责

| 层 | 负责 | 禁止 |
|---|---|---|
| controller | 参数校验、统一响应结构 | 写业务异常分支、暴露底层细节 |
| service | 定义业务失败语义、决定重试/降级/回滚 | 返回 null 代替明确失败 |
| repository | 保留数据访问失败事实、向上抛可识别异常 | 吞 DB 异常、把错误伪装成空数据 |

---

## 8. Review 检查项

- [ ] 是否存在吞异常 / 无意义 catch
- [ ] 是否丢失原始 cause
- [ ] 是否把失败伪装成成功（返回空集合/false）
- [ ] 异常类型分类是否正确（Biz / Sys / ThirdParty）
- [ ] 重试是否只用于可恢复异常
- [ ] 同一异常是否被重复打印
