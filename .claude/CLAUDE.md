# 项目协作入口

本文件只负责规则优先级、任务路由和加载入口。工程硬性要求以 rules 为准，执行步骤以 workflows 为准；不在各 Agent 文件重复维护规则。

## 1. 基本约束与优先级

- 使用中文交流；遵从用户本次明确要求，保留未提交改动，不自动提交。
- 超过 10 行的非简单修改先说明目标、范围与验证方案并确认；同一方案已确认后继续执行，不重复询问。
- 接手先读仓库 AGENTS.md、.doc/HANDOFF.md，核对 Git 状态和相关差异；交接记录是快照，当前代码与可重复验证优先。
- 工程约定优先于通用设计模板：[project-conventions.md](rules/project-conventions.md)。
- 注释通用原则见 [comment.md](rules/comment.md)；语言硬规则优先，Java 见 [comment-java.md](rules/comments/comment-java.md)。
- 长期背景按 [.claude/memory/MEMORY.md](memory/MEMORY.md) 索引读取，仅加载命中的主题。未经用户明确授权不修改 memory。
- 安全边界、日志、测试和架构要求不能用简化代码、减少注释或编译通过替代。

## 2. 加载规则

1. 必须读取本入口、项目约定和项目记忆索引；复杂任务再读取对应 Workflow。
2. 每个文件只有在当前会话已完整读取且内容未变，或明确作为完整上下文注入时，才算已加载。
3. Markdown 链接、索引、文件名、搜索命中和“已自动加载”的描述都不代表正文已加载。输出截断时继续按章节读取，不把截断视为完整读取。
4. 同一任务已读的规则复用；文件变化、任务换语言或换专题时补读适用内容，不机械重读整个目录。
5. Workflow 每次只加载当前阶段的 Agent 和适用规则；后续阶段进入时再读。普通问答不加载开发全链路。
6. Agent 是职责与检查视角。按阶段顺序执行即可，不因为文件里列出多个 Agent 就创建子代理；并行执行遵守仓库 worktree 与用户授权要求。
7. 开始修改前用一句话说明适用规则和范围，不要求在用户回复中重复完整路由表。

## 3. 任务路由

流程目录和专项规则触发条件统一见 [workflow.md](workflows/workflow.md)，本入口不复制流程正文。

| 任务 | 执行入口 |
|---|---|
| 普通知识问答、解释已有代码 | 读取相关代码；复杂领域问题按需加载 22-knowledge-qa |
| 新功能、修复、代码重构 | [代码变更流程](workflows/code-change-flow.md)，需要新需求设计时先进入 A |
| 新需求、接口设计 | [A 功能交付](workflows/feature-delivery-flow.md) |
| 安全、注入、依赖审计 | [B 安全审查](workflows/security-audit-flow.md) |
| 告警、故障、慢 SQL、配置排障 | [C 故障响应](workflows/incident-response-flow.md) |
| 技债治理 | [D 技债治理](workflows/tech-debt-flow.md) |
| 重构收尾 | [E 重构收尾](workflows/refactor-finalize-flow.md) |
| 全量测试、真实接口回归、联调报告 | [F 运行时回归](workflows/runtime-api-test-flow.md) |
| 规则、Workflow、文档整理 | 读取相应文档及引用者；遵守项目确认和范围约束，不自动改业务代码 |

同时命中多个场景时，按用户目标选择主流程，追加必要专题阶段；只在存在影响范围的真实歧义时澄清。具体见 [routing-policy.md](workflows/routing-policy.md)。

## 4. 交付门槛

代码变更必须完成 [代码变更流程](workflows/code-change-flow.md) 的规则加载、实现、逐方法注释/架构审计、验证与复核。
规则文件本身的修改必须检查本地链接、旧章节引用、相互矛盾的要求及加载触发条件。

- 检查失败或无法运行时如实记录原因，不写“通过”；未完成注释审计不能宣称代码交付完成。
- 回复先说明结果、验证和限制；不要强制所有任务输出五个英文栏目或无实际用途的 Next Agent。
- 重要任务完成后更新 .doc/HANDOFF.md，写当前结果、验证、风险和下一步，不追加聊天流水账。
