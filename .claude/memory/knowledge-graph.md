---
name: knowledge-graph
description: 主动检测项目知识图谱并在结构性问题时优先使用
metadata:
  type: reference
---

**每次对话开始时**，检查当前项目根目录是否存在 `.understand-anything/knowledge-graph.json`：

```bash
ls .understand-anything/knowledge-graph.json 2>/dev/null && echo "exists" || echo "not exists"
```

**存在时：** 回答以下类型问题时，优先 Read 此文件，而不是从头搜索源码：
- 项目架构、模块划分、层次结构
- 某个类/文件的职责与依赖关系
- 类之间的调用、继承、实现关系
- 某个业务流程涉及哪些文件
- 新增功能应放在哪个包/层

也可用 `/understand-chat` 技能做精准图谱查询。

**不存在时：** 忽略，正常搜索代码。如用户希望生成图谱，提示运行 `/understand`。
