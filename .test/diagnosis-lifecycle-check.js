// 实际页面与公共脚本回归；拦截所有业务接口，控制响应顺序与轮询时钟，不调用真实后台。
async page => {
    await page.unrouteAll({behavior:"wait"});
    const origin = "http://127.0.0.1:18765", errors = [], checks = [];
    const progress = {total:2, succeeded:2, failed:0, pending:0, running:0};
    const task = {id:27, planName:"生命周期测试", projectName:"测试项目", status:"SUCCEEDED", progress,
        channels:[{channelId:3, channelName:"测试模型", progress}], snapshot:{project:{name:"测试项目"}}};
    const report = {id:5, diagnosisTaskId:27, status:"SUCCEEDED", planName:task.planName, projectName:task.projectName,
        progress, summary:{sample:{}, models:[], strength:[], reservations:[], criteria:[], byIntent:[],
            concerns:[{dimension:"测试顾虑", count:2, examples:[{resultId:11, quote:"旧回答", channelName:"旧模型"}, {resultId:12, quote:"新回答", channelName:"新模型"}]}]}};
    const answers = [11, 12].map(id => ({id, questionSortOrder:id - 11, questionText:"问题" + id, channelId:3,
        channelName:"测试模型", repeatIndex:1, status:"SUCCEEDED", answerText:id === 11 ? "旧回答正文" : "新回答正文", citations:[]}));
    const waiting = new Map();
    const held = new Map();
    const waitForHold = key => new Promise(resolve => held.set(key, resolve));
    let holdAnswer = false, detailMode = "done", detailReads = 0, reportPosts = 0, postRelease = null;
    let notifyPost;
    const postStarted = new Promise(resolve => { notifyPost = resolve; });
    const candidatePages = [];
    page.on("pageerror", error => errors.push(error.message));
    await page.addInitScript(() => {
        const set = window.setTimeout.bind(window), clear = window.clearTimeout.bind(window);
        const timers = new Map(); let id = -1;
        window.setTimeout = (callback, delay, ...args) => {
            if (delay === 3000 || delay === 5000) { const key = id--; timers.set(key, {callback, delay}); return key; }
            return set(callback, delay, ...args);
        };
        window.clearTimeout = key => { timers.delete(key); clear(key); };
        window.__pollDelays = () => [...timers.values()].map(timer => timer.delay);
        window.__runPoll = async () => {
            const [key, timer] = [...timers][0]; timers.delete(key); await timer.callback();
        };
    });
    await page.route(origin + "/**", async route => {
        const url = new URL(route.request().url()), path = url.pathname;
        if (path.startsWith("/admin/")) return route.continue();
        if (path === "/favicon.ico") return route.fulfill({status:204});
        if (path === "/diagnosis-reports/diagnosis-tasks/101") {
            reportPosts++;
            await new Promise(resolve => { postRelease = resolve; notifyPost(); });
            return route.fulfill({json:report});
        }
        if (path === "/diagnosis-reports/5") {
            detailReads++;
            if (detailMode === "hold") {
                await new Promise(resolve => { waiting.set("detail", resolve); held.get("detail")?.(); });
                return route.fulfill({json:report}).catch(() => {});
            }
            if (detailMode === "fail") {
                detailMode = "running";
                return route.fulfill({status:503, json:{detail:"测试：临时不可用"}});
            }
            return route.fulfill({json:detailMode === "running" ? {...report, status:"RUNNING"} : report});
        }
        if (path === "/diagnosis-tasks/27/results/11" && holdAnswer) {
            await new Promise(resolve => { waiting.set("answer", resolve); held.get("answer")?.(); });
            return route.fulfill({status:503, json:{detail:"旧回答的过期错误"}}).catch(() => {});
        }
        let body;
        if (path === "/diagnosis-reports") body = {items:[report], total:1};
        else if (path === "/diagnosis-tasks") {
            const pageNumber = Number(url.searchParams.get("page"));
            candidatePages.push(pageNumber);
            const items = url.searchParams.get("size") === "100"
                ? Array.from({length:pageNumber === 0 ? 100 : 1}, (_, index) => ({...task, id:pageNumber * 100 + index + 1}))
                : [task];
            body = {items, total:url.searchParams.get("size") === "100" ? 101 : 1};
        } else if (path === "/diagnosis-tasks/27") body = task;
        else if (path === "/diagnosis-tasks/27/results") body = {items:answers, total:2};
        else if (/\/diagnosis-tasks\/27\/results\/(11|12)$/.test(path)) body = answers.find(answer => path.endsWith("/" + answer.id));
        else if (path === "/diagnosis-plans") body = {items:[{id:1, name:"测试方案", projectName:"测试项目"}], total:1};
        else if (path === "/llm-channels") body = {items:[], total:0};
        else throw new Error("未定义的测试接口：" + path);
        return route.fulfill({json:body});
    });
    // 第 101 条候选任务仍能通过跳转参数自动选中；提交中 ESC 不能关闭。
    await page.goto(origin + "/admin/history-report.html?diagnosisTaskId=101");
    await page.waitForFunction(() => document.getElementById("create-task").value === "101");
    if (await page.locator("#create-task option").count() !== 102 || !candidatePages.includes(1)) throw new Error("候选分页不完整");
    checks.push("超过100条候选完整读取并自动选中");
    await page.locator("#submit-create").click();
    await postStarted;
    await page.waitForFunction(() => document.getElementById("submit-create").disabled);
    await page.keyboard.press("Escape");
    if (!await page.locator("#create-report-dialog").evaluate(node => node.open)) throw new Error("提交中窗口被关闭");
    postRelease();
    await page.locator("#report-detail-title").filter({hasText:"#5"}).waitFor();
    checks.push("报告提交中保持窗口且仅提交一次");
    await page.getByRole("tab", {name:"② 可见度与推荐", exact:true}).click();
    // 旧回答晚到且报错，不能清空新回答或显示旧错误。
    holdAnswer = true;
    const oldAnswerHeld = waitForHold("answer");
    await page.getByRole("button", {name:"看回答", exact:true}).nth(0).click();
    await oldAnswerHeld;
    await page.waitForFunction(() => document.getElementById("answer-dialog").open);
    await page.locator("#close-answer").click();
    await page.getByRole("button", {name:"看回答", exact:true}).nth(1).click();
    await page.locator("#answer-content").getByText("新回答正文", {exact:true}).waitFor();
    waiting.get("answer")?.();
    await page.waitForFunction(() => document.getElementById("answer-content").textContent.includes("新回答正文") && document.getElementById("answer-error").hidden);
    checks.push("报告旧回答及旧错误不覆盖新回答");
    await page.keyboard.press("Escape");
    await page.waitForFunction(() => !document.getElementById("answer-dialog").open);
    if (!await page.evaluate(() => document.body.classList.contains("task-dialog-open"))) throw new Error("父窗口仍打开却解除滚动锁");
    await page.keyboard.press("Escape");
    await page.waitForFunction(() => !document.body.classList.contains("task-dialog-open"));
    checks.push("ESC逐层关闭并恢复背景滚动");
    // 关闭等待中的详情，旧响应不能填回已关闭窗口或恢复计时器。
    detailMode = "hold";
    const detailHeld = waitForHold("detail");
    await page.locator("#report-list").getByRole("button", {name:"查看", exact:true}).click();
    await detailHeld;
    await page.waitForFunction(() => document.getElementById("report-detail-dialog").open);
    const requestStarted = detailReads;
    await page.keyboard.press("Escape");
    await page.waitForFunction(() => !document.getElementById("report-detail-dialog").open);
    waiting.get("detail")?.();
    if (await page.evaluate(() => window.__pollDelays().length)) throw new Error("关闭后轮询未清理");
    if (detailReads !== requestStarted) throw new Error("关闭后多发详情读取");
    checks.push("关闭等待中的报告后不回填或恢复轮询");
    // 503提示并安排5秒重读，进行中转3秒，完成后停止。
    detailMode = "fail";
    await page.locator("#report-list").getByRole("button", {name:"查看", exact:true}).click();
    await page.getByText("测试：临时不可用", {exact:true}).waitFor();
    if (!(await page.evaluate(() => window.__pollDelays())).includes(5000)) throw new Error("失败未安排重读");
    await page.evaluate(() => window.__runPoll());
    if (!(await page.evaluate(() => window.__pollDelays())).includes(3000)) throw new Error("进行中未继续刷新");
    detailMode = "done";
    await page.evaluate(() => window.__runPoll());
    if (await page.evaluate(() => window.__pollDelays().length)) throw new Error("完成后未停止");
    if (!await page.locator("#detail-error").isHidden()) throw new Error("恢复后未清理旧错误");
    checks.push("临时失败提示并恢复，终态停止刷新");
    await page.goto(origin + "/admin/diagnosis-tasks.html");
    await page.locator("#task-list").getByRole("button", {name:"查看", exact:true}).click();
    await page.locator("#result-list").getByRole("button", {name:"查看回答", exact:true}).nth(0).waitFor();
    const taskAnswerHeld = waitForHold("answer");
    await page.locator("#result-list").getByRole("button", {name:"查看回答", exact:true}).nth(0).click();
    await taskAnswerHeld;
    await page.waitForFunction(() => document.getElementById("answer-dialog").open);
    await page.keyboard.press("Escape");
    await page.waitForFunction(() => !document.getElementById("answer-dialog").open);
    await page.locator("#result-list").getByRole("button", {name:"查看回答", exact:true}).nth(1).click();
    await page.locator("#answer-content").getByText("新回答正文", {exact:true}).waitFor();
    waiting.get("answer")?.();
    answers[1].answerText = "恢复后的回答";
    await page.evaluate(() => {
        window.dispatchEvent(new PageTransitionEvent("pagehide", {persisted:true}));
        window.dispatchEvent(new PageTransitionEvent("pageshow", {persisted:true}));
    });
    await page.locator("#answer-content").getByText("恢复后的回答", {exact:true}).waitFor();
    checks.push("页面缓存恢复后重新读取仍打开的回答");
    await page.keyboard.press("Escape");
    await page.waitForFunction(() => !document.getElementById("answer-dialog").open);
    await page.keyboard.press("Escape");
    await page.waitForFunction(() => !document.getElementById("task-detail-dialog").open && !document.body.classList.contains("task-dialog-open"));
    checks.push("任务回答切换与父子窗口清理");
    if (errors.length || reportPosts !== 1) throw new Error(JSON.stringify({errors, reportPosts}));
    await page.unrouteAll({behavior:"wait"});
    return {checks, pageErrors:errors};
}
