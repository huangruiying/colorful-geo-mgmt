# 前端检查

在仓库根目录执行，使用 Node 自带的测试运行器，无需安装 npm 依赖：

```sh
node --test src/test/js/*.test.cjs
```

- `http.test.cjs`：公共 JSON 请求边界，使用标准 Response 和可控网络替身。
- `browser-login.test.cjs`：登录方式切换、取消、状态及错误处理，使用虚拟 DOM 和接口。
- `async.test.cjs`：请求取代、取消、迟到结果/错误、轮询终态及错误重读，使用可控 Promise/时钟。
- `pagination.test.cjs`：完整选项分页、筛选、失败与取消。
- `dialog.test.cjs`：关闭保护、遮罩判断、滚动锁及迟到 close 事件。
- `presentation.test.cjs`：内容日期、发布状态、安全网页链接与原有平台草稿入口。
- `navigation.test.cjs`：11页菜单路径、唯一高亮、分组展开、面包屑及装饰图标。

以上不是浏览器或真实平台联调，也不包含 Java 测试。修改公共请求时，另外检查其所有调用页面的脚本依赖顺序与资源版本。

四个试点页面的浏览器回归脚本为 `.test/http-pages-check.js`，供 playwright-cli 的 `run-code --filename` 执行。先用本地静态服务器在 127.0.0.1:18765 提供 `src/main/resources/static`，再打开该来源的页面；脚本拦截全部业务接口，验证项目列表/创建、模型列表/启停/编辑、失败恢复和成功跳转，不写业务数据库。

诊断页生命周期回归为 `.test/diagnosis-lifecycle-check.js`，使用相同来源与接口拦截；浏览器内控制读轮询时钟和响应顺序，覆盖候选任务超过 100 条、旧回答、关闭清理、暂时错误恢复、终态停止、创建关闭保护和恢复事件。pagehide/pageshow 是模拟事件，不等同于真实浏览器缓存导航测试。Markdown 的原有页面检查仍见 `.test/markdown-view-check.js`。

第三批四页检查为 `.test/shared-pages-check.js`：方案编辑保存、内容优化原文、发布删除 204、实际登录页取消 204。全部接口替身，删除确认只在该测试页自动确认，不访问真实平台；登录方式与取消细节另由 Node 测试覆盖。

第四批导航检查为 `.test/navigation-layout-check.js`：11页×1440/390px视口、键盘展开及链接跳转、资源错误和JS异常。`.test/layout-snapshot.js` 用相同空接口夹具采集选定计算样式，第四批前后结果记录在 `.test/layout4-comparison.json`；它是本地浏览器基线，不是跨系统的像素级断言。截图在 `output/playwright/layout4-*`。
