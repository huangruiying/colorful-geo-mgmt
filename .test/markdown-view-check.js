// 使用 playwright-cli run-code --filename 执行；只拦截本地静态页面的接口，不访问业务服务。
async page => {
    const origin = "http://127.0.0.1:18765";
    const markdown = "# 诊断回答\n\n普通正文第一行\n第二行，包含 **重点建议**。\n\n## 推荐方案\n\n- 方案一\n- 方案二\n\n> 引用说明\n\n| 渠道 | 结论 |\n| --- | --- |\n| 豆包 | 推荐 |\n\n```java\nString value = \"<script>原样显示</script>\";\n```\n\n[查看资料](https://example.invalid/source)";
    const progress = {total:1, succeeded:1, failed:0, pending:0, running:0};
    const task = {id:27, planName:"测试方案", projectName:"测试项目", status:"SUCCEEDED", progress,
        channels:[{channelId:3, channelName:"测试渠道", progress}], snapshot:{project:{name:"测试项目"}}, createdAt:"2026-10-17T10:00:00"};
    const answer = {id:88, status:"SUCCEEDED", questionSortOrder:0, questionText:"测试问题", channelId:3,
        channelName:"测试渠道", repeatIndex:1, answerText:markdown, citations:[], finishReason:"completed", rawResponse:"{\"raw\":\"**不渲染**\"}"};
    const example = {dimension:"服务体验", count:1, examples:[{resultId:88, quote:"回答节选", channelName:"测试渠道"}]};
    const report = {id:1, diagnosisTaskId:27, status:"SUCCEEDED", planName:"测试方案", projectName:"测试项目", progress,
        summary:{sample:{}, models:[], strength:[], reservations:[example], criteria:[], concerns:[], byIntent:[]}};
    const content = {id:1, title:"正文测试", originalContent:markdown, optimizedContent:markdown, warnings:[], citations:[]};
    const errors = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.route(origin + "/**", async route => {
        const path = new URL(route.request().url()).pathname;
        if (path.startsWith("/admin/")) return route.continue();
        let body = {items:[], total:0};
        if (path === "/diagnosis-reports") body = {items:[report], total:1};
        else if (path === "/diagnosis-reports/1") body = report;
        else if (path === "/diagnosis-tasks") body = {items:[task], total:1};
        else if (path === "/diagnosis-tasks/27") body = task;
        else if (path === "/diagnosis-tasks/27/results") body = {items:[answer], total:1};
        else if (path === "/diagnosis-tasks/27/results/88") body = answer;
        else if (path === "/content-optimize/detail/1") body = {record:content, publishRecords:[]};
        else if (path === "/favicon.ico") return route.fulfill({status:204});
        await route.fulfill({json:body});
    });
    const checks = [];
    /** 检查四个页面实际插入的正文节点，而不只验证公共方法被调用。 */
    async function verifyBody(selector, name) {
        await page.locator(selector + " h1").waitFor();
        const result = await page.locator(selector).evaluate(node => ({
            heading:node.querySelector("h1")?.textContent,
            table:node.querySelectorAll("table").length,
            list:node.querySelectorAll("li").length,
            quote:node.querySelectorAll("blockquote").length,
            bold:node.querySelector("strong")?.textContent,
            code:node.querySelector("pre code")?.textContent,
            newTab:node.querySelector("a")?.getAttribute("target"),
            rel:node.querySelector("a")?.getAttribute("rel")
        }));
        if (result.heading !== "诊断回答" || result.table !== 1 || result.list !== 2 || result.quote !== 1
                || result.bold !== "重点建议" || !result.code.includes("<script>")
                || result.newTab !== "_blank" || !result.rel.includes("noopener")) {
            throw Error(name + " 渲染不符合预期：" + JSON.stringify(result));
        }
        checks.push(name);
    }

    // 1. 公共组件：普通文本换行、安全链接、HTML 转义，以及失败后可再次渲染。
    await page.goto(origin + "/admin/history-report.html");
    await page.waitForFunction(() => Boolean(window.MarkdownView));
    const component = await page.evaluate(() => {
        const node = document.createElement("div");
        MarkdownView.render(node, "普通文本第一行\n第二行\n\n下一段，数字 123。 ");
        if (node.querySelectorAll("br").length !== 1 || node.querySelectorAll("p").length !== 2) throw Error("普通文本换行丢失");
        const attacks = ["<script>window.__markdownAttack=1</script>", "<img src=x onerror=window.__markdownAttack=1>",
            "[危险](javascript:alert(1))", "[混淆](jav&#x61;script:alert(1))", "[文件](file:///etc/passwd)",
            "![图片](data:image/svg+xml;base64,PHN2Zy8+)"];
        for (const input of attacks) {
            MarkdownView.render(node, input);
            if (node.querySelector("script, iframe, object, embed, img, [onerror], [onclick]")) throw Error("危险内容被当作 DOM 执行");
            for (const link of node.querySelectorAll("a")) {
                if (!["https:", "http:", "mailto:"].includes(link.protocol)) throw Error("危险链接未过滤");
            }
        }
        MarkdownView.render(node, "[锚点](#section) [邮件](mailto:test@example.invalid) ![示意](https://example.invalid/image.png)");
        if (node.querySelector('a[href="#section"]').hasAttribute("target") || node.querySelector("img").referrerPolicy !== "no-referrer") throw Error("安全属性不符合预期");
        return {plainText:true, attacks:attacks.length, sourceHtmlEscaped:true, safeLinks:true};
    });
    checks.push("公共组件兼容与安全 " + JSON.stringify(component));

    // 2. 通过报告真实按钮打开回答，验证共享渲染及正文布局。
    await page.locator("#report-list button").click();
    await page.getByRole("tab", {name:"② 可见度与推荐"}).click();
    await page.getByRole("button", {name:"看回答", exact:true}).click();
    await verifyBody("#answer-content", "诊断报告回答");
    await page.setViewportSize({width:1440, height:1000});
    await page.screenshot({path:"output/playwright/markdown-report-desktop.png"});
    await page.setViewportSize({width:390, height:844});
    await page.screenshot({path:"output/playwright/markdown-report-mobile.png"});
    const overflow = await page.locator("#answer-dialog").evaluate(node => node.scrollWidth > node.clientWidth + 1);
    if (overflow) throw Error("窄屏回答弹窗横向溢出");
    const wideLayout = await page.evaluate(() => {
        const columns = Array(8).fill("很长的表格列名").join(" | ");
        const separators = Array(8).fill("---").join(" | ");
        MarkdownView.render(document.querySelector("#answer-content"), "| " + columns + " |\n| " + separators
                + " |\n| " + columns + " |\n\n```\n" + "long_code_".repeat(80) + "\n```");
        const dialog = document.querySelector("#answer-dialog");
        const pre = dialog.querySelector("pre");
        return {dialogOverflow:dialog.scrollWidth > dialog.clientWidth + 1, codeScroll:pre.scrollWidth > pre.clientWidth};
    });
    if (wideLayout.dialogOverflow || !wideLayout.codeScroll) throw Error("长表格或代码没有限制在正文内部滚动");
    checks.push("390px 窄屏长表格及长代码不撑开弹窗");

    // 3. 任务正文使用 Markdown，原始响应仍由既有 JSON 查看逻辑展示。
    await page.setViewportSize({width:1440, height:1000});
    await page.goto(origin + "/admin/diagnosis-tasks.html");
    await page.locator("#task-list button").click();
    await page.getByRole("button", {name:"查看回答", exact:true}).click();
    await verifyBody("#answer-content .answer-body", "诊断任务回答");
    await page.getByRole("button", {name:"查看原始响应", exact:true}).click();
    if (await page.locator("#information-content strong").count()) throw Error("原始 JSON 被 Markdown 渲染");
    if (!(await page.locator("#information-content pre").textContent()).includes("**不渲染**")) throw Error("原始 JSON 内容被修改");
    checks.push("原始响应保持原文");
    await page.getByRole("button", {name:"返回", exact:true}).click();
    await page.getByRole("button", {name:"返回任务", exact:true}).click();
    answer.status = "FAILED"; answer.errorMessage = "**失败原因保持原文**";
    await page.getByRole("button", {name:"查看回答", exact:true}).click();
    if (await page.locator("#answer-content .answer-body strong").count()
            || await page.locator("#answer-content .answer-body").textContent() !== answer.errorMessage) throw Error("失败原因被 Markdown 渲染");
    checks.push("失败提示保持原文");

    // 4. 内容和发布详情只改变视图，原文及编辑框不受渲染影响。
    await page.goto(origin + "/admin/contents.html");
    await page.evaluate(async () => { document.querySelector("textarea").value = "**编辑框原文**"; await openContentDetail(1); });
    await verifyBody("#original-content", "内容原文");
    await verifyBody("#optimized-content", "内容优化稿");
    if (await page.locator("textarea").first().inputValue() !== "**编辑框原文**") throw Error("编辑框内容改变");
    if (!(await page.evaluate(() => currentDetail.record.optimizedContent)).startsWith("# 诊断回答")) throw Error("提交源数据被替换成 HTML");
    checks.push("编辑框与提交原文不变");
    await page.goto(origin + "/admin/publications.html");
    await page.evaluate(text => openRecordDetail({id:1, contentOptimizationRecordId:1, publicationTitle:"测试正文", publicationContent:text}), markdown);
    await verifyBody("#detail-content", "发布正文");
    if (errors.length) throw Error("页面异常：" + errors.join("; "));
    return {passed:checks, pageErrors:errors, screenshots:["markdown-report-desktop.png", "markdown-report-mobile.png"]};
}
