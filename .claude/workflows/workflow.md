# Agent Registry

## planners（需求与架构）

| 编号 | Agent                        | 用途      |
|----|------------------------------|---------|
 01 | requirements-breakdown       | 需求拆解    
 02 | architecture-review          | 架构评审    
 03 | api-contract-design          | API设计   
 24 | system-design-tradeoff       | 架构取舍    
 25 | architecture-decision-record | ADR决策记录 

---

## builders（研发实现）

| 编号 | Agent             | 用途       |
|----|-------------------|----------|
 04 | code-generation   | 代码生成     
 05 | refactor          | 代码重构     
 06 | unit-test-gen     | 单元测试生成   
 07 | doc-gen           | 文档生成     
 27 | db-schema-review  | 数据库结构评审  
 31 | runtime-api-test  | 接口回归测试

---

## guardians（质量与安全）

| 编号 | Agent             | 用途       |
|----|-------------------|----------|
 08 | vuln-scan         | 漏洞扫描     
 09 | code-review       | 代码评审     
 10 | injection-xss     | 注入与XSS检测 
 11 | dependency-audit  | 依赖审计     
 12 | perf-hotspot      | 性能热点分析   
 26 | threat-modeling   | 威胁建模     

---

## responders（故障响应）

| 编号 | Agent                       | 用途      |
|----|-----------------------------|---------|
 14 | log-rca                     | 日志根因分析  
 15 | alert-triage                | 告警分诊    
 16 | slow-sql                    | 慢SQL分析  
 17 | config-drift                | 配置漂移检测  
 28 | distributed-troubleshooting | 分布式排障   
 29 | cost-optimization           | 成本优化    

---

## stewards（治理知识）

| 编号 | Agent             | 用途       |
|----|-------------------|----------|
 20 | tech-debt         | 技术债治理    
 21 | pr-summary        | PR摘要     
 22 | knowledge-qa      | 知识问答     
 23 | oncall-knowledge  | 值班知识沉淀   
 30 | agent-router      | Agent路由调度 

---

# Workflow 索引与加载时机

| 流程 | 目标与触发 | 入口 |
|---|---|---|
| 公共代码变更 | 生成、修复、重构，注释及架构验收 | [代码变更](code-change-flow.md) |
| A | 新需求、功能或接口设计；需求确认后进入公共代码变更 | [功能交付](feature-delivery-flow.md) |
| B | 安全、注入、依赖风险 | [安全审查](security-audit-flow.md) |
| C | 告警、故障、慢 SQL、配置与链路排障 | [故障响应](incident-response-flow.md) |
| D | 技债识别、治理规划与实施 | [技债治理](tech-debt-flow.md) |
| E | 已有改动的重构收尾 | [重构收尾](refactor-finalize-flow.md) |
| F | 用户要求全量测试、真实接口回归、联调报告 | [运行时回归](runtime-api-test-flow.md) |

## 专题角色懒加载

- 普通知识问题只读相关代码，必要时读 22；不展开实现流程。
- 架构设计读 02，取舍读 24/25，表设计读 27，文档生成读 07。
- 其他 Agent 仅在当前流程实际进入对应职责时读取。
- 工程规则集中在 rules；角色文件只声明职责、执行动作与结果，不复制规则正文。
- 编码和评审使用同一份 [注释与架构验收步骤](code-change-flow.md)，不能各自维护不同标准。
- 多候选主流程选择见 [routing-policy.md](routing-policy.md)，不强制用户选择内部 Agent 编号。
- 用户未要求创建任务或并行代理时，以当前任务内顺序切换职责完成工作。

## 输出要求

按用户任务报告结果、证据和限制；代码变更须给出公共流程要求的验收记录。
不强制每次输出五个英文栏目或 Next Agent，不在回复中重复路由和规则正文。
