# AGENTS.md — colorful-geo-mgmt 共同协作入口

本文件供 Codex/GPT、WorkBuddy 等开发工具共同使用。始终用中文交流；用户明确要求其他语言时除外。

## 接手顺序

1. 阅读本文件与 [当前交接](.doc/HANDOFF.md)，再检查 `git status`、最近提交和相关 `git diff`。交接是快照；代码、数据库实际状态和可重复测试优先。
2. 必读 [项目架构与代码约定](.claude/rules/project-conventions.md)，再阅读 [.claude/CLAUDE.md](.claude/CLAUDE.md) 与 [.claude/memory/MEMORY.md](.claude/memory/MEMORY.md)。项目约定优先于通用模板；按任务加载其他适用规则，不要把旧仓库路径当成当前路径。
3. 先明确本次目标、已完成与未完成部分，再做最小范围修改；复杂开发先给出方案并与用户确认。

## 开发与交接约束

- 接口输入对象命名为 `XxxRequest`，输出对象命名为 `XxxResponse`；只有独立于接口的业务命令才使用 `XxxCommand`，不为命名增加 Request 到 Command 的中间转换。
- Spring 依赖注入始终使用 `jakarta.annotation.Resource` 字段注入，不使用构造器注入或 `@RequiredArgsConstructor` 注入依赖；依赖注入后的初始化使用 `@PostConstruct`。
- 项目是独立的 Java 17 / Spring Boot 服务；POM、目录和运行配置以当前仓库为准。
- 修改 Java 时遵守 `.claude/rules/comment.md`、`.claude/rules/comments/comment-java.md` 和 `.claude/rules/refactor.md`；同步修正失真的注释，适当使用 Lombok，避免无用抽象。
- 保留用户未提交改动；未经明确要求，不自动提交、重置、清理或批量格式化。
- 不在文档、日志、测试输出中写入 API Key、Cookie、验证码、数据库会话或带令牌的后台链接。
- 区分“代码已实现”“单元测试通过”“真实平台联调通过”；未证实的结果标为待验证，不把草稿成功写成公开发布成功。
- 一项重要工作结束或交给另一工具前，更新 `.doc/HANDOFF.md` 的当前任务、进度、改动、验证、风险和下一步；不要追加聊天流水账。长期规则留在本文件和 `.claude/`，临时任务只写交接文档。
- 同一工作目录顺序交接；确需并行时使用不同分支或 worktree，避免两个工具同时改同一文件。工具开始工作时重新核对交接与 Git 差异。
