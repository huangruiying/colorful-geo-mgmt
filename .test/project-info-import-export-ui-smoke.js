/** 本地静态页面的浏览器冒烟；接口使用合成数据替身，不调用真实数据库或模型。由 playwright-cli run-code --filename 执行。 */
async page => {
    const check = (condition, message) => { if (!condition) { throw new Error(message); } };
    let importCalls = 0;
    let csrfHeader;
    const pageErrors = [];
    page.on("pageerror", error => pageErrors.push(error.message));
    await page.route("**/projects?**", route => route.fulfill({json: {items: [{id: 10, name: "演示项目"}], total: 1, page: 0, size: 20}}));
    await page.goto("http://127.0.0.1:18624/admin/project-info-import-export.html");
    await page.context().addCookies([{name: "XSRF-TOKEN", value: "synthetic-csrf", url: "http://127.0.0.1:18624"}]);
    await page.route("**/project-info-import-export/10", route => route.fulfill({json: {format: "colorful-geo-project", version: 1, project: [{id: "9007199254740993", name: "演示项目"}]}}));
    await page.route("**/project-info-import-export", async route => {
        importCalls++;
        csrfHeader = route.request().headers()["x-xsrf-token"];
        if (importCalls === 1) {
            await route.fulfill({status: 400, json: {detail: "请先配置渠道编码：MISSING"}});
        } else {
            await route.fulfill({status: 201, json: {projectId: "101", projectName: "<script>synthetic</script>", counts: {project: 1, plans: 1, questions: 2, planChannels: 1, tasks: 1, results: 2, reports: 1, analyses: 2}}});
        }
    });
    const downloadPromise = page.waitForEvent("download");
    await page.getByRole("button", {name: "导出项目", exact: true}).click();
    const download = await downloadPromise;
    check(download.suggestedFilename() === "project-10.json", "下载文件名错误");
    const stream = await download.createReadStream();
    let content = "";
    for await (const chunk of stream) { content += chunk.toString(); }
    check(content.includes("9007199254740993"), "导出文件的大整数 ID 丢失精度");
    await page.getByLabel("选择项目 JSON 文件").setInputFiles({name: "project.json", mimeType: "application/json", buffer: Buffer.from('{"format":"colorful-geo-project","version":1}')});
    await page.getByRole("button", {name: "导入为新项目", exact: true}).click();
    await page.getByRole("alert").filter({hasText: "MISSING"}).waitFor();
    check(importCalls === 1, "失败后不应自动重试");
    await page.getByRole("button", {name: "导入为新项目", exact: true}).click();
    await page.locator("#import-result").waitFor({state: "visible"});
    check((await page.locator("#import-result").textContent()).includes("新项目 ID：101"), "未展示新项目");
    check((await page.locator("#import-result").textContent()).includes("诊断结果 2 条"), "未展示导入数量");
    check(await page.locator("#import-result script").count() === 0, "项目名称不应解析为 HTML");
    check(csrfHeader === "synthetic-csrf", "写请求缺少跨站令牌");
    check(await page.getByRole("button", {name: "导入为新项目", exact: true}).isEnabled(), "操作完成未恢复按钮");
    await page.setViewportSize({width: 1440, height: 1000});
    await page.screenshot({path: "output/playwright/project-info-import-export-desktop.png", fullPage: true});
    await page.setViewportSize({width: 390, height: 844});
    check(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), "窄屏页面出现整体横向溢出");
    await page.screenshot({path: "output/playwright/project-info-import-export-mobile.png", fullPage: true});
    check(pageErrors.length === 0, "页面存在 JavaScript 异常");
    return {passed: ["download", "large ID", "missing-channel error", "no retry", "CSRF", "import counts", "XSS", "controls", "desktop/mobile layout"], pageErrors};
}
