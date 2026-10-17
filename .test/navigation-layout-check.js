// 全部页面导航与布局回归；空接口替身，不写业务数据。
async page => {
    await page.unrouteAll({behavior:"wait"});
    const origin = "http://127.0.0.1:18765", errors = [], failures = [], checks = [];
    page.on("pageerror", error => errors.push(error.message));
    page.on("response", response => { if (response.url().startsWith(origin + "/admin/") && response.status() >= 400) failures.push(response.status() + " " + response.url()); });
    await page.route(origin + "/**", async route => {
        const path = new URL(route.request().url()).pathname;
        if (path.startsWith("/admin/") && !path.startsWith("/admin/browser-login/platforms/")) return route.continue();
        return route.fulfill({json:path.includes("/browser-login/platforms/") || path.endsWith("/strategies") ? [] : {items:[], total:0}});
    });
    const pages = ["projects", "project-create", "models", "model-config", "diagnose", "diagnosis-tasks", "history-report", "contents", "publications", "browser-login", "effect-recovery"];
    for (const width of [1440, 390]) {
        await page.setViewportSize({width, height:1000});
        for (const name of pages) {
            await page.goto(origin + "/admin/" + name + ".html?unrelated=1");
            const selected = page.locator(".navigation [aria-current=page]");
            await selected.waitFor();
            if (await page.locator(".navigation-item").count() !== 11) throw new Error("菜单数不正确：" + name);
            const href = await selected.getAttribute("href");
            if (new URL(href, page.url()).pathname !== "/admin/" + name + ".html") throw new Error("高亮路径不正确：" + name);
            if (await page.locator(".navigation-group[open]").count() !== 1) throw new Error("默认展开组不唯一：" + name);
            if (!await selected.isVisible()) throw new Error("当前菜单不可见：" + name);
            if (await page.locator(".breadcrumb strong").textContent() !== (await selected.textContent()).trim().slice(1)) throw new Error("面包屑不一致：" + name);
            const styles = await page.evaluate(() => [...document.styleSheets].flatMap(sheet => [...sheet.cssRules]).filter(rule => rule.type === CSSRule.IMPORT_RULE).length);
            if (styles) throw new Error("存在隐式CSS导入：" + name);
            checks.push(name + "@" + width);
            if (name === "models" && width === 1440) await page.screenshot({path:"output/playwright/layout4-models-desktop.png", fullPage:true});
            if (name === "project-create" && width === 390) await page.screenshot({path:"output/playwright/layout4-form-mobile.png", fullPage:true});
        }
    }
    // 原生details仍可用键盘展开，普通站内链接保持页面导航。
    await page.setViewportSize({width:1440, height:1000});
    await page.goto(origin + "/admin/models.html");
    const summary = page.locator(".navigation-group").first().locator("summary");
    await summary.focus(); await page.keyboard.press("Enter");
    await page.getByRole("link", {name:"项目列表", exact:true}).click();
    await page.waitForURL("**/projects.html?*");
    if (errors.length || failures.length) throw new Error(JSON.stringify({errors, failures}));
    await page.unrouteAll({behavior:"wait"});
    return {scenes:checks.length, keyboardAndNavigation:true, pageErrors:errors, resourceErrors:failures};
}
