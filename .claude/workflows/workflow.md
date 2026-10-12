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

# 场景路由规则（Dispatch）

路由规则以 `CLAUDE.md §3` 场景自动识别表为权威来源（已自动加载），此处不重复维护，避免两处漂移。

---

# 标准 Workflow

> 每条 workflow 的主表只放"短代号 + 链路图"，详细阶段定义、输出契约、严守边界请打开各自的 `<short-code>-flow.md`。多候选 / 自定义编排规则见 `routing-policy.md`。

## Workflow A 功能交付链 feature-delivery

详见 `workflows/feature-delivery-flow.md`。

```text
01 requirements-breakdown
↓
03 api-contract-design
↓
04 code-generation
↓
06 unit-test-gen
↓
09 code-review
↓
如用户要求全量测试 → 31 runtime-api-test
↓
21 pr-summary
```

---

## Workflow B 安全审查链 security-audit

详见 `workflows/security-audit-flow.md`。

```text
08 vuln-scan
├─→10 injection-xss
├─→11 dependency-audit
└─→09 code-review
```

---

## Workflow C 线上故障链 incident-response

详见 `workflows/incident-response-flow.md`。

```text
15 alert-triage
↓
14 log-rca
├─→16 slow-sql
├─→17 config-drift
├─→28 distributed-troubleshooting
└─→09 code-review
↓
23 oncall-knowledge
```

---

## Workflow D 技债治理链 tech-debt

详见 `workflows/tech-debt-flow.md`。

```text
20 tech-debt
├─→05 refactor
│   ↓
│  06 unit-test-gen
│   ↓
│  09 code-review
├─→11 dependency-audit
└─→02 architecture-review
↓
21 pr-summary
```

---

## Workflow E 重构收尾链 refactor-finalize

详见 `workflows/refactor-finalize-flow.md`。

```text
P1 消 IDE 警告
↓
P2 抽方法合并
↓
P3 横向铺开
↓
06 unit-test-gen
↓
09 code-review
```

---

## Workflow F 运行时接口回归链 runtime-api-test

详见 `workflows/runtime-api-test-flow.md`。

```text
31 runtime-api-test
├─→14 log-rca
├─→04 code-generation
├─→05 refactor
↓
31 runtime-api-test
↓
21 pr-summary
```

---

# Agent 调度原则

简单问题：

单 Agent 处理。

复杂问题：

必须多 Agent 串联。

跨域问题：

优先 Workflow，而不是单 Agent。

---

# 输出契约（所有 Agent 统一）

统一输出：

## Selected Agent(s)

选择了哪些 Agent

## Findings

发现

## Risks

风险

格式：

[严重度:CRITICAL/HIGH/MEDIUM/LOW]
文件:行号
问题
修复建议

## Recommended Actions

建议动作

## Next Agent

建议下一跳 Agent

---
