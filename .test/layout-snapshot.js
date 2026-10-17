// 抽取前后使用相同页面、空接口夹具和视口，比较实际计算样式；不触发业务写入。
async page => {
    await page.unrouteAll({behavior:"wait"});
    const origin = "http://127.0.0.1:18765";
    await page.route(origin + "/**", async route => {
        const path = new URL(route.request().url()).pathname;
        if (path.startsWith("/admin/") && !path.startsWith("/admin/browser-login/platforms/")) return route.continue();
        const array = path.includes("/browser-login/platforms/") || path.endsWith("/strategies");
        return route.fulfill({json:array ? [] : {items:[], total:0}});
    });
    const pages = ["projects", "project-create", "models", "model-config", "diagnose", "diagnosis-tasks", "history-report", "contents", "publications", "browser-login", "effect-recovery"];
    const results = {};
    for (const width of [1440, 390]) {
        await page.setViewportSize({width, height:1000});
        for (const name of pages) {
            await page.goto(origin + "/admin/" + name + ".html");
            await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
            results[name + "@" + width] = await page.evaluate(() => {
                const values = {};
                for (const selector of [".sidebar", ".primary-button", ".text-button", "main input", "main table", "dialog"]) {
                    const node = document.querySelector(selector);
                    if (!node) continue;
                    const style = getComputedStyle(node);
                    values[selector] = [style.fontSize, style.padding, style.backgroundColor, style.minWidth, style.minHeight, style.width, style.borderRadius];
                }
                const dialog = document.querySelector("dialog");
                if (dialog) {
                    dialog.showModal();
                    const style = getComputedStyle(dialog);
                    values.openDialog = [style.width, style.maxHeight, style.padding, style.borderRadius];
                    dialog.close();
                }
                return values;
            });
        }
    }
    await page.unrouteAll({behavior:"wait"});
    return results;
}
