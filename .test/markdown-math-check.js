// 使用本地页面验证报告 #5 中的公式结构；不修改业务数据。
async page => {
    await page.goto("http://127.0.0.1:18765/admin/history-report.html");
    const result = await page.evaluate(() => {
        const node = document.createElement("div");
        document.body.replaceChildren(node);
        const source = "## 背后大致怎么计算\n\n常见的每升优惠价可以理解为：\n\n\\[\n\\text{App显示优惠价}\n=\n\\text{油站枪价}\n-\n\\text{油站每升直降}\n-\n\\text{平台每升补贴}\n\\]\n\n\\[7.80\\times40-0.60\\times40-20=268\\text{元}\\]\n\n普通文字换行\n仍然保留。\n\n```text\n\\[\\text{代码中的公式}\\]\n```";
        MarkdownView.render(node, source);
        const checks = {formulas:node.querySelectorAll("math").length === 2,
            noFalseHeadings:node.querySelectorAll("h1,h2").length === 1,
            codePreserved:node.querySelector("code").textContent.includes("\\text{代码中的公式}"),
            lineBreak:!!node.querySelector("p br")};
        const hostile = document.createElement("div");
        MarkdownView.render(hostile, String.raw`\[\href{javascript:alert(1)}{危险}\]`);
        checks.safeMath = !hostile.querySelector("a,script,img,[onclick]");
        if (Object.values(checks).some(value => !value)) throw new Error(JSON.stringify(checks));
        return checks;
    });
    await page.screenshot({path:"output/playwright/markdown-math.png", fullPage:true});
    return result;
}
