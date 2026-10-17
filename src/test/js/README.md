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

以上不是浏览器或真实平台联调，也不包含 Java 测试。修改公共请求时，另外检查其所有调用页面的脚本依赖顺序与资源版本。

四个试点页面的浏览器回归脚本为 `.test/http-pages-check.js`，供 playwright-cli 的 `run-code --filename` 执行。先用本地静态服务器在 127.0.0.1:18765 提供 `src/main/resources/static`，再打开该来源的页面；脚本拦截全部业务接口，验证项目列表/创建、模型列表/启停/编辑、失败恢复和成功跳转，不写业务数据库。

诊断页生命周期回归为 `.test/diagnosis-lifecycle-check.js`，使用相同来源与接口拦截；浏览器内控制读轮询时钟和响应顺序，覆盖候选任务超过 100 条、旧回答、关闭清理、暂时错误恢复、终态停止、创建关闭保护和恢复事件。pagehide/pageshow 是模拟事件，不等同于真实浏览器缓存导航测试。Markdown 的原有页面检查仍见 `.test/markdown-view-check.js`。
