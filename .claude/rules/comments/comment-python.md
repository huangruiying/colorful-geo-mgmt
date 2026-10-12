## Python 注释硬性规则

1. 文件头部注释用于说明模块职责、核心流程、不负责的边界。
2. 类注释用于说明核心抽象、使用场景、生命周期或边界。
3. 有参数或有返回值的方法，必须写完整四段：功能 / 作用 / 参数 / 返回。
4. 对外主流程方法、编排方法、批处理方法包含 3 个以上连续业务阶段时，必须用编号注释标出核心流程。
5. 关键片段注释必须解释业务规则，禁止只翻译代码。

---

## 文件头部注释

```python
"""
订单风控主流程

负责：
1. 风险识别
2. 黑名单校验
3. 支付风控拦截

不负责：
- 支付扣款
- 库存处理
"""
```

---

## 类注释

```python
class RiskEngine:
    """
    风控规则执行器

    负责统一调度：
    - 黑名单规则
    - 设备规则
    - 支付规则

    不负责：
    - 支付扣款
    - 库存处理
    """
```

---

## 方法注释

### 强制规则

有参数或有返回值的方法，必须写完整四段：功能 / 作用 / 参数 / 返回。

禁止：

- 用单行 docstring 代替，如 `"""一句话描述"""`
- 有入参但省略参数段
- 有返回值但省略返回段
- 参数名后不对齐描述列
- 返回段只写一行描述，无法说明不同返回 case

例外：无参数、无返回值、且名称已完全自解释的极简私有函数，可省略。

### 示例

```python
def _get_session_mtime(payload: dict) -> str:
    """
    功能:
        返回 session 文件的 mtime。
    作用:
        用 session 文件的 mtime 作为账号标识。
        - 切换账号后 session 文件会被重写，mtime 变化后 cache key 随之变化
        - 不读文件内容，避免每次算 key 都做 IO 和哈希

    参数:
        payload:    服务端下发的参数

    返回:
        mtime:
            nochannel   没有 channel 标识
            nosession   没有 session 文件
            xxxxxx      真实有效的 mtime

    注意:
        不负责触发重新登录。
    """
    return "xxx"
```

---

## 业务代码注释

### 主流程阶段注释

对外主流程方法、编排方法、批处理方法，如果包含 3 个以上连续业务阶段，必须使用编号注释标出核心流程。

格式选择：

- 普通业务主流程：使用 `# 1. xxx`
- 生命周期、收尾、并发清理等需要视觉分隔的大段流程：使用 `# ===== 1. xxx =====`
- 同一方法内只能使用一种阶段注释格式，避免主流程风格漂移

适用场景：

- `generate` / `execute` / `run_batch` / `parse_batch` / `analyze_batch` 等主流程方法
- 一段方法内同时包含读取数据、调用外部服务、转换数据、落库、返回结果
- 多个步骤顺序有业务含义，后续维护者需要快速理解执行链路

正确示例：

```python
# 1. 读取批次上下文，确定本次分析目标
batch = await self._batch_repo.get_by_id(batch_id)

# 2. 收集前三步产出的明细数据，作为分析输入
runs = await self._run_repo.list_by_batch_id(batch_id)
extractions = await self._extraction_repo.list_by_batch_id(batch_id)

# 3. 计算品牌、引用、批次总览指标
summary_item = _build_batch_summary_item(...)

# 4. 覆盖写入分析结果，保证重复执行得到同一批次最新快照
summary = await self._summary_repo.replace_by_batch(batch_id, summary_item)
```

禁止：

- 主流程方法只有方法 docstring，没有步骤注释
- 用"处理数据"、"执行逻辑"这类泛化描述代替具体业务步骤
- 每一行都加注释，导致主流程被噪音淹没

### 生命周期 / 收尾流程注释

生命周期、收尾、并发清理流程可以使用分隔格式，注释必须说明执行顺序和原因。

```python
# ===== 1. 解绑 response 监听，避免关闭期间继续接收新响应 =====
self.page.remove_listener("response", self._response_listener)

# ===== 2. 等待已进入的 handler 收尾，避免 body 读取被中途打断 =====
active_handlers = list(self._active_handlers)
if active_handlers:
    await asyncio.wait_for(
        asyncio.gather(*active_handlers, return_exceptions=True),
        timeout=3.0,
    )

# ===== 3. 清理上下文，释放本次监听生命周期资源 =====
self._active_handlers.clear()
```

### 关键片段注释

以下 Python 片段必须在代码块前写一行业务注释，说明"按什么规则筛选 / 转换 / 入袋"，禁止只靠读代码猜意图：

- 列表推导 / 集合推导中包含业务过滤条件
- 从一批数据里拆出"已存在 / 待新增 / 待处理 / 失败项"
- 为幂等、防重复、兼容历史数据做过滤
- 字段转换、归一化、序列化、反序列化

```python
# 只保留数据库中还不存在的解析结果，避免 run_id 唯一索引冲突
insert_items = [
    item for item in items
    if item["run_id"] not in existing_run_ids
]
```

### 兼容和兜底注释

兼容旧数据、历史字段、默认兜底时，注释必须说明为什么需要该分支。

```python
if "device_id" not in payload:
    # 兼容旧版客户端字段缺失问题，避免灰度期间设备规则全部失配
    payload["device_id"] = "unknown"
```

---

## 禁止示例

```python
# 遍历数据
for item in items:
    ...

# 返回结果
return result
```

这类注释只翻译代码字面含义，应改成业务原因。
