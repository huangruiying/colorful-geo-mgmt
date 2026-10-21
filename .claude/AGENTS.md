# AGENTS.md — Codex 项目入口

> 本文件只做 Codex 入口约束，不复制完整工程规则。
> 完整规则、Agent 路由、Workflow、注释、命名、重构、日志、异常处理，统一以 `.claude/CLAUDE.md` 为唯一规则源。

## 语言要求

始终使用中文与用户交流，包括解释、说明、步骤、总结和提问；除非用户明确要求使用其他语言。

## 本地文件读取

当用户提供本地绝对路径时，优先直接读取本地文件。
除非读取工具实际报错，否则不要先假设无法访问本地文件，也不要要求用户手动粘贴文件内容。

## 项目规则入口

开始处理任务前，必须先读取：

1. `.claude/CLAUDE.md`
2. `.claude/memory/MEMORY.md`
3. 按 `.claude/CLAUDE.md` 的路由结果，继续加载对应 agents / workflows / rules 文件

## 强制约束

- 不允许绕过 `.claude/CLAUDE.md` 直接执行复杂开发任务
- 除非是 10 行以内的简单修改，否则先给出规划并与用户确认，再进入开发
- 生成或修改代码时，必须按 `.claude/rules/comment.md` 与 `.claude/rules/comments/comment-xxx.md` 执行注释规范
- 生成或修改代码时，必须按 `.claude/rules/refactor.md` 执行重构规范
- 修改代码时，必须同步更新真实含义已经变化的注释
- 永远不要自动提交 git commit，除非用户明确要求

## 项目记忆维护

`.claude/memory/` 只记录长期有效、跨会话有复用价值的内容，不记录临时实现细节。

当发现用户反馈、工程决策或协作偏好可能值得长期沉淀时，先判断归属：

- 强执行规范：建议写入 `.claude/rules/`
- 项目背景 / 设计决策 / 协作偏好：建议写入 `.claude/memory/`
- 当前任务细节：不写入 memory

除非用户明确要求“写入 memory / 记录下来 / 更新规则文件”，否则不要直接修改 `.claude/memory/`；先说明建议沉淀位置并等待确认。

## graphify

- **graphify** (`~/.Codex/skills/graphify/SKILL.md`) - any input to knowledge graph. Trigger: `/graphify`
- 当用户输入 `/graphify` 时，先调用 graphify skill，再处理其他任务。
