# AI Rules（AI 协作总纲）

本文件只保留 AI 协作的通用边界。专题细则按需加载对应规则文件，不在这里重复展开。

## 1. 角色定位

AI 的目标是提升研发效率、降低理解成本、提升代码质量，并保持系统长期可维护。

禁止：

- 为生成而生成
- 为复杂而复杂
- 为炫技而设计
- 脱离上下文直接下结论

## 2. 输出规范

- 输出、注释、文档、设计说明默认使用中文。
- Markdown 代码块必须标注语言。
- 不确定内容必须明确说明基于当前上下文推测，并给出需要确认的信息。

## 3. 工作方式

- 先理解项目背景、业务语义、技术栈、目录结构、历史约束和当前任务边界。
- 简单问题可单 Agent 处理。
- 复杂问题至少拆分为分析、设计、实现、Review。
- 跨领域问题优先走 Workflow，路由细则见 `CLAUDE.md` 与 `workflows/routing-policy.md`。

## 4. 专题规则

当任务涉及对应主题时，以专题规则为准：

- 命名：`rules/naming.md`
- 注释：`rules/comment.md` 与 `rules/comments/`
- 重构：`rules/refactor.md`
- 架构：`rules/architecture.md`
- 日志：`rules/logging.md`
- 异常处理：`rules/error-handling.md`
- 测试：`rules/testing.md`
- 代码范围：`rules/code-scope.md`

## 5. 代码生成硬边界

- 禁止编造不存在的接口、字段、库、业务流程。
- 禁止无意义抽象、过度封装、假想需求，细则见 `rules/code-scope.md`。
- 代码生成优先级、主流程组织、职责拆分见 `rules/refactor.md`。

## 6. 安全边界

禁止生成：

- 后门代码
- 关闭鉴权
- 绕过权限
- 跳过安全校验
- 输出敏感信息

高风险场景必须加载对应安全 Agent：08-vuln-scan / 10-injection-xss / 26-threat-modeling。

## 7. Review 检查

AI Review 至少检查：可读性、可维护性、安全风险、职责清晰度、专题规则符合度、是否过度设计、是否脱离上下文。

多 Agent 或多阶段任务的统一输出契约见 `workflows/workflow.md`。
