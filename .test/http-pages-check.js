// 通过 playwright-cli run-code --filename 执行；静态页面是真实源码，业务接口全部拦截。
async page => {
    const origin = "http://127.0.0.1:18765";
    const errors = [], checks = [];
    const project = {id:12, name:"请求组件测试项目", industry:"测试行业", websiteUrl:"https://example.invalid", createdAt:"2026-10-17T10:00:00"};
    const model = {id:18, channelCode:"test-channel", channelName:"请求组件测试模型", providerCode:"test",
        strategyBeanName:"chatCompletionsApiCommonStrategy", protocolType:"OPENAI_CHAT_COMPLETIONS", modelName:"test-model",
        baseUrl:"https://example.invalid/v1", enabled:true, score:1, maxTokens:1000, temperature:null, keyConfigured:true};
    let projectSaves = 0, modelSaves = 0, enabled = true;
    page.on("pageerror", error => errors.push(error.message));
    await page.route(origin + "/**", async route => {
        const request = route.request(), url = new URL(request.url()), path = url.pathname;
        if (path.startsWith("/admin/")) return route.continue();
        if (path === "/favicon.ico") return route.fulfill({status:204});
        if (path === "/projects" && request.method() === "POST") {
            projectSaves++;
            if (request.postDataJSON().name !== project.name) throw new Error("项目提交内容改变");
            return projectSaves === 1 ? route.fulfill({status:400, json:{detail:"测试：项目保存失败"}})
                : route.fulfill({status:201, json:project});
        }
        if (path === "/llm-channels/18" && request.method() === "PUT") {
            modelSaves++;
            const body = request.postDataJSON();
            if (body.apiKey !== "" || body.channelCode !== model.channelCode || body.temperature !== null) throw new Error("模型编辑提交契约改变");
            return modelSaves === 1 ? route.fulfill({status:409, json:{detail:"测试：模型保存失败"}})
                : route.fulfill({json:model});
        }
        if (path === "/llm-channels/18/enabled") {
            enabled = request.postDataJSON().enabled;
            return route.fulfill({json:{...model, enabled}});
        }
        let body;
        if (path === "/projects") body = {items:[project], total:1};
        else if (path === "/llm-channels/strategies") body = [{beanName:model.strategyBeanName, protocolType:model.protocolType}];
        else if (path === "/llm-channels/18") body = model;
        else if (path === "/llm-channels") body = {items:[{...model, enabled}], total:1};
        else throw new Error("未定义的测试接口：" + path);
        return route.fulfill({json:body});
    });
    await page.goto(origin + "/admin/projects.html");
    await page.getByRole("cell", {name:project.name, exact:true}).waitFor();
    checks.push("项目列表加载");
    await page.goto(origin + "/admin/project-create.html");
    for (const [name, value] of Object.entries({name:project.name, websiteUrl:project.websiteUrl, industry:project.industry,
        coreFeatures:"测试功能", targetAudience:"测试用户"})) await page.locator(`[name="${name}"]`).fill(value);
    await page.locator("#create-project").click();
    await page.getByText("测试：项目保存失败", {exact:true}).waitFor();
    if (await page.locator("#create-project").isDisabled()) throw new Error("项目保存失败未恢复按钮");
    checks.push("项目失败原因及按钮恢复");
    await page.locator("#create-project").click();
    await page.waitForURL("**/projects.html?*created=12");
    await page.getByText("项目创建成功，项目 ID：12", {exact:true}).waitFor();
    checks.push("项目创建成功跳转");
    await page.goto(origin + "/admin/models.html");
    await page.getByRole("cell", {name:model.channelName, exact:true}).waitFor();
    await page.getByRole("button", {name:"停用", exact:true}).click();
    await page.getByRole("button", {name:"启用", exact:true}).waitFor();
    checks.push("模型列表及启停请求");
    await page.goto(origin + "/admin/model-config.html?id=18");
    await page.getByText("已保存密钥，留空保留；填写新密钥可替换。", {exact:true}).waitFor();
    await page.locator("#save-model").click();
    await page.getByText("测试：模型保存失败", {exact:true}).waitFor();
    if (await page.locator("#save-model").isDisabled()) throw new Error("模型保存失败未恢复按钮");
    checks.push("模型失败原因、按钮恢复及空密钥保留");
    await page.locator("#save-model").click();
    await page.waitForURL("**/models.html?*saved=18");
    await page.getByText("模型配置已保存，渠道 ID：18", {exact:true}).waitFor();
    checks.push("模型编辑成功跳转");
    if (projectSaves !== 2 || modelSaves !== 2 || errors.length) throw new Error(JSON.stringify({projectSaves, modelSaves, errors}));
    await page.unrouteAll({behavior:"wait"});
    return {checks, pageErrors:errors};
}
