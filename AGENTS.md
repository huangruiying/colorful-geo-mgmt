# AGENTS.md — — colorful-geo-mgmt 共同协作入口 Codex 项目入口

> 本文件只做 Codex 入口约束，不复制完整工程规则。
> 完整规则、Agent 路由、Workflow、注释、命名、重构、日志、异常处理，统一以 `.claude/CLAUDE.md` 为唯一规则源。
> 本文件供 Codex/GPT、WorkBuddy 等开发工具共同使用。始终用中文交流；用户明确要求其他语言时除外。

## 语言要求

始终使用中文与用户交流，包括解释、说明、步骤、总结和提问；除非用户明确要求使用其他语言。

## 本地文件读取

当用户提供本地绝对路径时，优先直接读取本地文件。
除非读取工具实际报错，否则不要先假设无法访问本地文件，也不要要求用户手动粘贴文件内容。

## 接手顺序

1. 阅读本文件与 [当前交接](.doc/HANDOFF.md)，再检查 `git status`、最近提交和相关 `git diff`。交接是快照；代码、数据库实际状态和可重复测试优先。
2. 必读 [项目架构与代码约定](.claude/rules/project-conventions.md)，再阅读 [.claude/CLAUDE.md](.claude/CLAUDE.md)
   与 [.claude/memory/MEMORY.md](.claude/memory/MEMORY.md)。项目约定优先于通用模板；按任务加载其他适用规则，不要把旧仓库路径当成当前路径。

## 项目规则入口

开始处理任务前，必须先读取：

1. `.claude/CLAUDE.md`
2. `.claude/memory/MEMORY.md`
3. 按 `.claude/CLAUDE.md` 的路由结果，继续加载对应 agents / workflows / rules 文件

## 强制约束

- 不允许绕过 `.claude/CLAUDE.md` 直接执行复杂开发任务
- 除非是 10 行以内的简单修改，否则先给出规划并与用户确认，再进入开发
- 先明确本次目标、已完成与未完成部分，再做最小范围修改；复杂开发先给出方案并与用户确认。
- 生成或修改代码时，必须按 `.claude/rules/comment.md` 与 `.claude/rules/comments/comment-xxx.md` 执行注释规范
- 生成或修改代码时，必须按 `.claude/rules/refactor.md` 执行重构规范
- 修改代码时，必须同步更新真实含义已经变化的注释
- 永远不要自动提交 git commit，除非用户明确要求

## 开发约束

- 接口输入对象命名为 `XxxRequest`，输出对象命名为 `XxxResponse`；只有独立于接口的业务命令才使用 `XxxCommand`，不为命名增加
  Request 到 Command 的中间转换。
- Spring 依赖注入始终使用 `jakarta.annotation.Resource` 字段注入，不使用构造器注入或 `@RequiredArgsConstructor`
  注入依赖；依赖注入后的初始化使用 `@PostConstruct`。
- 项目是独立的 Java 17 / Spring Boot 服务；POM、目录和运行配置以当前仓库为准。
- 修改 Java 时遵守 `.claude/rules/comment.md`、`.claude/rules/comments/comment-java.md` 和 `.claude/rules/refactor.md`
  ；同步修正失真的注释，适当使用 Lombok，避免无用抽象。
- 保留用户未提交改动；未经明确要求，不自动提交、重置、清理或批量格式化。
- 不在文档、日志、测试输出中写入 API Key、Cookie、验证码、数据库会话或带令牌的后台链接。
- 区分“代码已实现”“单元测试通过”“真实平台联调通过”；未证实的结果标为待验证，不把草稿成功写成公开发布成功。
- 一项重要工作结束或交给另一工具前，更新 `.doc/HANDOFF.md` 的当前任务、进度、改动、验证、风险和下一步；不要追加聊天流水账。长期规则留在本文件和
  `.claude/`，临时任务只写交接文档。
- 同一工作目录顺序交接；确需并行时使用不同分支或 worktree，避免两个工具同时改同一文件。工具开始工作时重新核对交接与 Git 差异。

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



