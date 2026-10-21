# 重构规范

> 本文件聚焦重构专有规则（§1-§4 主流程/职责/长度/终止，§11-§12 生命周期/状态机，§16 代码组织，§20-§21 AI 生成优先级与速查 HARD RULES）。
> 其他规范见专题文件（按需读取）：命名 → `naming.md` / 注释 → `comment.md` / 日志 → `logging.md` / 异常 → `error-handling.md` / 架构 → `architecture.md` / 范围约束 → `code-scope.md`

核心原则：

```text
可读性 > 可维护性 > 炫技
```

---

# CORE RULES（速查，细则见各专题文件）

```text
- 主流程必须线性化（§1）
- 方法必须职责单一（§2）
- 方法名必须业务化，固定动词表 + 编排类条件 → naming.md §3.2
- 注释必须解释”为什么”（comment.md）
- 禁止无语义抽象（code-scope.md）
- 禁止 utils 垃圾场（§15）
- 禁止过度设计（code-scope.md）
- 方法内复杂的逻辑，按业务或职能边界，用换行分开
```

---

# 1. 主流程规范

主流程必须像"操作手册"：先做什么、再做什么、最后做什么。避免把大量 `if / for / while / try` 直接堆在主方法里。

---

# 2. 方法职责规范

一个方法只负责一个职责。校验、构造、持久化、通知等职责应拆成语义清晰的小方法。

---

# 2.1 核心流程与非核心流程识别

写代码或抽取方法时，必须先判断该逻辑属于核心流程还是非核心流程。

核心流程：

- 影响业务结果正确性
- 影响状态流转
- 影响资金、订单、导出文件等主产物
- 失败时必须阻断主流程

非核心流程：

- 进度日志
- 统计埋点
- 辅助诊断信息
- 非必要通知
- 临时文件清理等兜底动作

规则：

- 核心流程异常必须向上暴露，禁止吞掉或伪装成成功
- 非核心流程不得阻断主流程
- 非核心流程抽成方法时，方法内部必须自行 `try-catch`
- `catch` 后记录 `warn` 日志，说明非核心动作失败及关键上下文
- 禁止在主流程里散落非核心 `try-catch`，优先抽成语义明确的方法

---

# 3. 方法长度规范

方法尽量控制在 10-30 行。长逻辑必须拆分，拆分后的方法名要表达业务动作，命名细则见 `rules/naming.md`。

---

# 4. 提前终止规范

业务失败优先使用明确异常表达，禁止 return 分支散落导致主流程被噪音污染。异常分类、catch 位置、日志边界见 `rules/error-handling.md`。

---

# 5. 方法命名规范

→ `rules/naming.md`

---

# 6. 特殊命名例外

→ `rules/naming.md`

---

# 7. 注释规范

见 `rules/comment.md`（原则）及 `rules/comments/` 下语言特化示例，按需读取。

---

# 8. 文件头注释规范

见 `rules/comment.md` 的各层说明 及 `rules/comments/comment-java.md` / `rules/comments/comment-python.md`，按需读取。

---

# 9. 类注释规范

见 `rules/comment.md` 的各层说明 及 `rules/comments/comment-java.md` / `rules/comments/comment-python.md`，按需读取。

---

# 10. 方法注释规范

见 `rules/comment.md` 的各层说明 及 `rules/comments/comment-java.md` / `rules/comments/comment-python.md`，按需读取。

---

# 11. 生命周期注释规范

并发、监听、资源收尾等生命周期代码必须说明执行顺序。具体注释格式见 `rules/comment.md` 与语言专项规则。

---

# 12. 状态机规范

状态机、生命周期流转必须显式声明关键状态与迁移关系。

---

# 13. 日志规范

见 `rules/logging.md`（按需读取），日志级别、必打场景、禁止打印内容以 logging.md 为准。

---

# 14. 常量规范

→ `rules/naming.md`

---

# 15. 工具类规范

工具类必须：无业务状态、无业务语义、可全局复用。命名规则 → `rules/naming.md`。

---

# 16. 代码组织规范

方法按调用链组织，主入口在前，紧随其后的私有方法按调用顺序排列；禁止随机排列。

---

# 17. 分层规范

见 `rules/architecture.md`（按需读取），controller/service/domain/repository 职责分层以 architecture.md 为准。

---

# 18. 异常处理规范

见 `rules/error-handling.md`（按需读取），禁止吞异常、捕获明确类型、保留原始 cause 以 error-handling.md 为准。

---

# 19. 禁止过度设计

见 `rules/code-scope.md §2.3~§2.4`（按需读取），禁止为设计模式而设计、过度抽象、过度封装、无意义分层。

---

# 20. AI GENERATION PRIORITY

生成代码时优先级：

```text
可读性
> 主流程清晰
> 职责单一
> 命名语义化
> 可维护性
> 性能优化
> 炫技
```

---

# 21. HARD RULES

```text
- 禁止巨型方法
- 禁止巨型类
- 禁止 utils 垃圾场
- 禁止超过3层嵌套
- 禁止 return 分支散落
- 禁止无意义注释
- 禁止过度抽象
- 命名规则 → naming.md
```

---

# 22. 最终目标

优秀代码应该：

```text
- 看起来像操作手册
- 主流程一眼可懂
- 业务动作明确
- 方法职责单一
- 命名通俗易懂
- 生命周期清晰
- 状态流转明确
```
