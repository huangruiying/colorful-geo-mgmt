# Markdown 正文展示检查

日期：2026-10-17。本次只改静态页面、脚本和样式，不修改后端、数据库或实际提交/发布的正文。

## 组件和应用位置

- 公共入口：`MarkdownView.render(container, value)`，位于 `static/admin/markdown-view.js`；共享样式为 `markdown-view.css`。
- 页面：`history-report.html` 的报告回答、`diagnosis-tasks.html` 的任务回答、`contents.html` 的原文/优化稿、`publications.html` 的最终发布正文。
- 不渲染：错误提示、列表字段、报告指标/证据短句、JSON 原始响应、配置快照、编辑框。渲染不回写接口对象，不改变后端提交数据。
- 基础能力：标题、列表、表格、引用、代码块、链接和图片；普通文本保留换行。Markdown 特征符号仍按语法解释，不承诺与逐字原文展示完全相同。

## 依赖与边界

- 固定版本 markdown-it 15.0.3，UMD 文件来自官方 npm 包 `https://registry.npmjs.org/markdown-it/-/markdown-it-15.0.3.tgz` 中 `dist/browser/markdown-it.umd.min.js`，只去掉未随库提供的 sourceMappingURL 注释。文件随静态资源提供，不从浏览器外连 CDN。
- MIT 许可随 `vendor/markdown-it-LICENSE.txt` 保存。当前本地库 SHA-256：`69137b0d504d407bab1abc9b6be9d8ad036b5bf7e002878a3a454db7f4775540`。
- `html:false`：原始 HTML 作为文本转义，只使用固定的 Markdown 和公式解析器。链接同时经过解析器默认校验及 HTTP/HTTPS/mailto 协议限制；新窗口链接隔离 opener，锚点不新开窗口。
- 图片按需加载且不发送 referrer，data/file/javascript 等协议被禁止。普通换行启用，智能标点和裸链接自动识别关闭。
- 仅正文容器使用 `.markdown-body`；代码和宽表在内部横向滚动，不撑开弹窗；解析失败时退回纯文本，不丢失原文。

## 验证

- 报告 #5 的用户顾虑例子对应任务 27、结果 210；只读接口确认原文使用 `\[...\]`，独立减号被误识别为标题。补充本地 KaTeX 0.19.0 与 markdown-it-texmath 1.0.0（官方 npm 包，MIT 许可随库保留），先解析公式，再解析 Markdown。使用原生 MathML，无远程字体；trust:false 禁止公式 HTML、外链和图片能力，限制宏展开与尺寸。
- `.test/markdown-math-check.js` 验证公式、标题误判、代码块原文、普通换行、危险公式链接共 5 项通过，截图 `output/playwright/markdown-math.png` 已检查；原有 10 组页面检查仍通过。四页资源版本更新为 20261017-md2。

- 组件、四个业务 JS 和本地库共 6 个脚本 `node --check` 通过。
- `.test/markdown-view-check.js` 通过 Playwright CLI 执行真实静态页面，所有业务 API 用本地固定数据拦截，不调用真实模型或业务服务。
- 10 组检查通过：公共文本/安全、报告回答、390px 长表格和长代码布局、任务回答、原始 JSON、失败提示、内容原文、优化稿、编辑框与提交源数据、发布正文。安全检查包含脚本、HTML 事件属性、javascript、实体混淆协议、file 和 data 图片共 6 组样例。
- 四个真实页面入口均确认生成标题/列表/表格/引用/代码和安全链接；浏览器无 JS 异常。桌面 1440px 和窄屏 390px 截图已检查：`output/playwright/markdown-report-desktop.png`、`markdown-report-mobile.png`。
- 限定文件差异检查通过。没有运行 Java 编译或测试，因为本次没有修改 Java。未重启业务服务、未验证实际数据库数据的页面联调、未提交 Git。

## 注释与架构复核

| 方法/位置 | 职责与阶段检查 | 结果 |
|---|---|---|
| MarkdownView#render | 原文转字符串→固定解析器生成视图→异常退回纯文本；明确不持久化、不改源数据 | 通过 |
| validateLink 回调 | 默认协议检查→URL 解析→允许协议；混淆和不合法地址拒绝 | 通过 |
| link_open/image 渲染回调 | 仅增加安全属性后使用库渲染，不插入原始 HTML；说明窗口/图片边界 | 通过 |
| 报告 openAnswer | 原异步读取和错误显示不变，只有回答正文交公共组件 | 通过 |
| 任务 loadAnswer | 状态/正文/引用及元数据流程不变，失败文本与原始响应不使用 Markdown | 通过 |
| openContentDetail | 原文和优化稿共用视图，currentDetail 与预发布编辑原文不变 | 通过 |
| openRecordDetail | 已发布快照只读转换，不发平台请求或修改发布数据 | 通过 |
| 四页 HTML/CSS | 依赖顺序固定，组件和页面资源同版本，正文样式隔离 | 通过 |

抽象由 5 个真实正文展示位置共用，只有一个入口和一个样式文件；没有增加后端接口、业务流程、框架构建工具或数据库字段。用户原有后端及未跟踪的诊断报告实现保留。
