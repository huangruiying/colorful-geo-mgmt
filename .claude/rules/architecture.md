# 分层架构规范

> 本项目具体分层与已确认设计以 [project-conventions.md](project-conventions.md) 为准。以下是通用参考，不要求为每个 CRUD 建立独立领域模型或额外转发层。

## java分层

### java代码推荐分层：

```text id="x6fll0"
controller
  ↓
service
  ↓
domain
  ↓
repository
```

---

### 各层职责

| 层          | 职责           |
|------------|--------------|
| controller | 请求接收、参数校验、响应 |
| service    | 业务编排         |
| domain     | 核心业务逻辑       |
| repository | 数据访问         |

---

## Repository 幂等边界

当某个写入动作需要“已存在则跳过”时，幂等细节应收敛在 repository / dao 层。

推荐：

```text
service
  ↓
repository.bulk_create_ignore_existing(items)
  ↓
返回 inserted_rows + skipped_count
```

职责划分：

| 层          | 职责                                  |
|------------|---------------------------------------|
| service    | 决定要保存哪些业务结果                 |
| repository | 判断哪些记录已存在、执行插入、返回跳过数量 |
| database   | 通过唯一索引兜底，防止并发重复写入       |

禁止：

- service/controller 手写多段“查存在 → 过滤 → 插入”逻辑
- repository 只返回空结果，让调用方猜测是无数据还是重复跳过
- 没有唯一约束却宣称写入幂等

---

### 禁止

* controller 写核心业务
* repository 写业务逻辑
* service 拼SQL

---
