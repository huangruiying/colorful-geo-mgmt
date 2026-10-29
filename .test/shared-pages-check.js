// 第三批页面回归：实际静态页面，全部业务接口替身，不写数据库或触发平台操作。
async page => {
    await page.unrouteAll({behavior:"wait"});
    const origin = "http://127.0.0.1:18765", errors = [], checks = [];
    const project = {id:1, name:"复用测试项目"};
    const channel = {id:3, channelCode:"test", channelName:"测试模型", modelName:"test-model", enabled:true, strategyBeanName:"chatCompletionsApiCommonStrategy"};
    const plan = {id:8, name:"复用测试方案", projectId:1, projectName:project.name, repeatCount:1, channelIds:[3],
        questions:[{text:"测试问题", source:"MANUAL", intent:"RECOMMENDATION", brandScope:"NON_BRAND", language:"中文"}]};
    const record = {id:9, title:"优化测试", originalContent:"原文", optimizedContent:"**优化正文**", warnings:[], citations:[],
        publishRecordCount:0, draftCount:0, processingCount:0, failedCount:0};
    const publication = {id:10, contentOptimizationRecordId:9, platformDisplayName:"测试平台", platformType:"TEST",
        publishStatus:"PRE_PUBLISHED", publicationTitle:record.title, publicationContent:record.optimizedContent, draftUrl:"https://example.invalid/draft", remoteContentId:"123"};
    let deleted = false, savedPlan = null, optimized = null, deleteCalls = 0;
    page.on("pageerror", error => errors.push(error.message));
    await page.route(origin + "/**", async route => {
        const request = route.request(), url = new URL(request.url()), path = url.pathname;
        if (path.startsWith("/admin/") && !path.startsWith("/admin/browser-login/platforms/")) return route.continue();
        if (path === "/favicon.ico") return route.fulfill({status:204});
        if (path === "/content-publish/detail/10" && request.method() === "DELETE") {
            deleted = true; deleteCalls++; return route.fulfill({status:204});
        }
        if (path === "/diagnosis-plans/8" && request.method() === "PUT") {
            savedPlan = request.postDataJSON(); return route.fulfill({json:{...plan, ...savedPlan}});
        }
        if (path === "/content-optimize/optimize") {
            optimized = request.postDataJSON(); return route.fulfill({json:record});
        }
        let body;
        if (path === "/projects") body = {items:[project], total:1};
        else if (path === "/llm-channels") body = {items:[channel], total:1};
        else if (path === "/diagnosis-plans") body = {items:[plan], total:1};
        else if (path === "/diagnosis-plans/8") body = plan;
        else if (path === "/content-optimize/list") body = {items:[record], total:1};
        else if (path === "/content-publish/list") body = {items:deleted ? [] : [publication], total:deleted ? 0 : 1};
        else if (path === "/admin/browser-login/platforms/login-statuses") body = [{platformType:"ZHIHU", platformName:"知乎", supported:true, status:"NOT_LOGGED_IN", loginMethods:["qr"], scanMethods:["PLATFORM_APP"]}];
        else if (path === "/admin/browser-login/platforms/ZHIHU/login/cancel") return route.fulfill({status:204});
        else if (path === "/admin/browser-login/platforms/ZHIHU/login") body = {status:"LOGIN_PENDING"};
        else throw new Error("未定义的测试接口：" + path);
        return route.fulfill({json:body});
    });
    await page.goto(origin + "/admin/diagnose.html");
    await page.locator("#plan-list").getByRole("button", {name:"编辑", exact:true}).click();
    await page.locator("#plan-form [name=name]").fill("更新后的方案");
    await page.locator("#save-plan").click();
    await page.getByText("方案已保存，ID：8。尚未执行诊断。", {exact:true}).waitFor();
    if (savedPlan?.name !== "更新后的方案" || savedPlan.questions[0].text !== "测试问题" || savedPlan.channelIds[0] !== 3) throw new Error("方案提交内容改变");
    checks.push("方案选项、编辑及保存字段保持");
    await page.goto(origin + "/admin/contents.html");
    await page.locator("#open-optimize").click();
    await page.locator("#optimize-form [name=title]").fill("优化测试");
    await page.locator("#optimize-form [name=content]").fill("**待优化原文**");
    await page.locator("#submit-optimize").click();
    await page.waitForFunction(() => !document.getElementById("optimize-dialog").open);
    if (optimized?.content !== "**待优化原文**" || optimized.title !== "优化测试") throw new Error("优化原文被展示组件改变");
    checks.push("内容优化提交原文保持");
    await page.goto(origin + "/admin/publications.html");
    await page.getByRole("cell", {name:"预发布成功", exact:true}).waitFor();
    const draft = page.getByRole("link", {name:"打开草稿", exact:true});
    if (await draft.getAttribute("rel") !== "noopener noreferrer") throw new Error("草稿安全属性丢失");
    // 确认框内容不是本次检查目标；只在此已拦截全部接口的测试页确认本地删除。
    await page.evaluate(() => { window.confirm = () => true; });
    await page.getByRole("button", {name:"删除", exact:true}).click();
    await page.getByText("本地发布记录 #10 已删除，平台内容未受影响。", {exact:true}).waitFor();
    if (deleteCalls !== 1) throw new Error("删除请求重复");
    checks.push("发布状态、草稿链接及删除204空响应");
    await page.goto(origin + "/admin/browser-login.html");
    await page.getByRole("button", {name:"登录", exact:true}).click();
    await page.waitForFunction(() => document.getElementById("login-feedback").textContent.includes("等待登录"));
    await page.locator("#cancel-login").click();
    await page.getByText("已取消本次登录；数据库中已有登录态未删除。", {exact:true}).waitFor();
    checks.push("实际登录页请求地址及取消204空响应");
    if (errors.length) throw new Error(JSON.stringify(errors));
    await page.unrouteAll({behavior:"wait"});
    return {checks, pageErrors:errors};
}
