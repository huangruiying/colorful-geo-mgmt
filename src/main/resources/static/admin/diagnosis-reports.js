"use strict";
(() => {
    const byId = id => document.getElementById(id);
    const createDialog = byId("create-report-dialog"), detailDialog = byId("report-detail-dialog"), answerDialog = byId("answer-dialog");
    const labels = {PENDING:"待生成", RUNNING:"生成中", SUCCEEDED:"已完成", FAILED:"失败"};
    const strengthLabels = {FIRST_CHOICE:"首推", STRONG:"强推", PARALLEL:"并列", PASSING:"顺带一提", WITH_RESERVATION:"附带缺点", NOT_RECOMMENDED:"不推荐"};
    const intentLabels = {RECOMMENDATION:"推荐选择", PROBLEM_SOLVING:"解决问题", COMPARISON:"对比", PURCHASE:"购买决策", TECHNICAL:"技术细节", AWARENESS:"了解认知"};
    // 子 Tab 与诊断报告全景一致；③–⑥ 的判定数据已保存，汇总与展示后续接入
    const tabs = [["overview", "① 诊断概览"], ["visibility", "② 可见度与推荐"], ["brand", "③ 品牌认知"], ["competitor", "④ 竞品对比"], ["source", "⑤ 引用与内容"], ["advice", "⑥ 优化建议"]];
    let page = 0, listTimer = null, detailTimer = null, listRevision = 0, detailRevision = 0;
    let report = null, activeTab = "overview", creating = false;
    const active = status => status === "PENDING" || status === "RUNNING";
    const time = value => value?.replace("T", " ") || "—";
    const percent = value => value == null ? "—" : (value * 100).toFixed(1) + "%";
    const show = (id, text) => { byId(id).textContent = text; byId(id).hidden = !text; };
    const element = (tag, className, text) => {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    };
    async function request(path, options = {}) {
        const response = await fetch(path, {cache:"no-store", headers:{"Content-Type":"application/json"}, ...options});
        let body;
        try { body = await response.json(); } catch { throw new Error("服务响应异常，请刷新后重试"); }
        if (!response.ok) throw new Error(body.detail || "操作失败，请检查输入或服务状态");
        return body;
    }
    function statusBadge(status) {
        const span = element("span", "task-status", labels[status] || status);
        span.dataset.status = status;
        return span;
    }
    function progressText(progress) {
        const done = progress.succeeded + progress.failed;
        return done + " / " + progress.total + (progress.failed ? "（失败 " + progress.failed + "）" : "");
    }
    // 表格单元格统一用文本节点写入，模型原文不作为 HTML 解析
    function table(headers, rows, metricFrom = 1) {
        const wrapper = element("div", "table-scroll"), tableNode = element("table", "report-table");
        const head = tableNode.createTHead().insertRow();
        headers.forEach((text, index) => { const cell = element("th", index >= metricFrom ? "metric" : null, text); cell.scope = "col"; head.append(cell); });
        const body = tableNode.createTBody();
        if (!rows.length) { const cell = body.insertRow().insertCell(); cell.colSpan = headers.length; cell.className = "table-message"; cell.textContent = "暂无数据"; }
        for (const values of rows) {
            const row = body.insertRow();
            values.forEach((value, index) => { const cell = row.insertCell(); if (index >= metricFrom) cell.className = "metric"; cell.textContent = value; });
        }
        wrapper.append(tableNode);
        return wrapper;
    }
    function section(title, help, ...children) {
        const node = element("section", "report-section");
        node.append(element("h3", null, title));
        if (help) node.append(element("p", "help", help));
        node.append(...children);
        return node;
    }

    async function loadList() {
        clearTimeout(listTimer);
        const revision = ++listRevision;
        const query = new URLSearchParams({page, size:10});
        if (byId("filter-status").value) query.set("status", byId("filter-status").value);
        const result = await request("/diagnosis-reports?" + query);
        if (revision !== listRevision) return;
        const rows = byId("report-list"); rows.replaceChildren();
        if (!result.items.length) {
            const cell = rows.insertRow().insertCell(); cell.colSpan = 6; cell.className = "table-message";
            cell.textContent = "暂无诊断报告，点击「生成诊断报告」选择一次已结束的诊断任务。";
        }
        for (const item of result.items) {
            const row = rows.insertRow();
            row.insertCell().textContent = "#" + item.id;
            const name = row.insertCell();
            name.append(element("span", null, (item.planName || "—") + " / " + (item.projectName || "—")), element("span", "task-secondary", "诊断任务 #" + item.taskId));
            row.insertCell().append(statusBadge(item.status));
            row.insertCell().textContent = progressText(item.progress);
            row.insertCell().textContent = time(item.createdAt);
            const view = element("button", "text-button", "查看"); view.type = "button";
            view.addEventListener("click", () => openDetail(item.id).catch(error => show("report-error", error.message)));
            row.insertCell().append(view);
        }
        byId("report-page-info").textContent = "第 " + (page + 1) + " 页 · 共 " + result.total + " 份";
        byId("report-previous").disabled = page === 0;
        byId("report-next").disabled = (page + 1) * 10 >= result.total;
        // 有报告在生成时定期刷新进度
        if (result.items.some(item => active(item.status))) listTimer = setTimeout(() => loadList().catch(() => {}), 3000);
    }

    async function openCreate(preselectedTaskId) {
        show("create-error", "");
        const select = byId("create-task");
        select.replaceChildren(new Option("正在加载…", ""));
        createDialog.showModal();
        const result = await request("/diagnosis-tasks?page=0&size=100");
        // 只有已结束且有成功回答的任务才能生成报告，与服务端校验一致
        const tasks = result.items.filter(task => task.status === "SUCCEEDED" || task.status === "PARTIAL_FAILED");
        select.replaceChildren(new Option(tasks.length ? "请选择诊断任务" : "暂无可生成报告的诊断任务", ""));
        for (const task of tasks) {
            select.add(new Option("#" + task.id + " · " + (task.planName || "—") + " · " + (task.projectName || "—")
                + " · 成功 " + task.progress.succeeded + " 条 · " + time(task.createdAt), task.id));
        }
        if (preselectedTaskId && tasks.some(task => String(task.id) === preselectedTaskId)) select.value = preselectedTaskId;
    }

    async function openDetail(id) {
        clearTimeout(detailTimer);
        report = null; activeTab = "overview"; show("detail-error", "");
        byId("report-detail-title").textContent = "诊断报告 #" + id;
        byId("report-panel").replaceChildren(element("p", "help", "正在加载…"));
        if (!detailDialog.open) detailDialog.showModal();
        await refreshDetail(id);
    }

    async function refreshDetail(id) {
        clearTimeout(detailTimer);
        const revision = ++detailRevision;
        const detail = await request("/diagnosis-reports/" + id);
        if (revision !== detailRevision || !detailDialog.open) return;
        report = detail;
        const judge = detail.judgeChannel ? (detail.judgeChannel.channelName || "—") + " · " + (detail.judgeChannel.modelName || "—") : "未开始";
        byId("report-detail-note").textContent = (detail.planName || "—") + " / " + (detail.projectName || "—") + " · 诊断任务 #" + detail.taskId
            + " · 判定模型：" + judge + " · " + (labels[detail.status] || detail.status);
        renderTabs();
        renderPanel();
        // 生成中每 3 秒刷新判定进度，完成后停止
        if (active(detail.status)) detailTimer = setTimeout(() => refreshDetail(id).catch(error => show("detail-error", error.message)), 3000);
    }

    function renderTabs() {
        const container = byId("report-tabs"); container.replaceChildren();
        for (const [key, title] of tabs) {
            const tab = element("button", "report-tab", title); tab.type = "button"; tab.setAttribute("role", "tab");
            tab.setAttribute("aria-selected", String(key === activeTab));
            tab.dataset.pending = String(!["overview", "visibility"].includes(key));
            tab.addEventListener("click", () => { activeTab = key; renderTabs(); renderPanel(); });
            container.append(tab);
        }
    }

    function renderPanel() {
        const panel = byId("report-panel"); panel.replaceChildren();
        if (report.status !== "SUCCEEDED") {
            const progress = element("div", "report-progress");
            if (report.status === "FAILED") progress.append(element("strong", null, "报告生成失败：" + (report.errorMessage || "原因未知")));
            else progress.append(element("strong", null, labels[report.status] + "：已判定 " + progressText(report.progress)));
            progress.append(element("p", "help", "报告完成后展示指标；判定使用已启用渠道中分数最高的模型，失败的回答不计入依赖判定的指标。"));
            panel.append(progress);
            return;
        }
        if (activeTab === "overview") renderOverview(panel, report.summary);
        else if (activeTab === "visibility") renderVisibility(panel, report.summary);
        else panel.append(element("div", "report-progress", "本部分待接入：所需的判定数据已随本报告保存，后续版本补充汇总与展示。"));
    }

    function renderOverview(panel, summary) {
        const sample = summary.sample, judge = sample.judgeChannel || {};
        const grid = element("div", "task-overview");
        for (const [label, value] of [["有效回答", sample.validAnswers], ["判定成功", sample.judged], ["判定失败", sample.judgeFailed],
            ["带来源回答", sample.withSources], ["实际联网回答", sample.searched], ["模型数", sample.models], ["题数", sample.questions],
            ["最大采样次数", sample.maxRepeat], ["判定模型", (judge.channelName || "—") + " · " + (judge.modelName || "—") + " · 提示词 " + (sample.promptVersion || "—")]]) {
            const item = element("div"); item.append(element("span", null, label), element("strong", null, String(value ?? "—"))); grid.append(item);
        }
        panel.append(section("样本说明", "只分析成功且未被截断的回答；依赖判定的指标只统计判定成功的回答。", grid));
        panel.append(section("各模型核心指标", "提及率、推荐率、首推率按非品牌问题计算；品牌正面率按点名问题计算；推荐份额 = 目标被推荐次数 ÷（目标 + 预设竞品被推荐次数）。",
            table(["模型", "判定回答", "提及率", "推荐率", "首推率", "品牌正面率", "推荐份额"], summary.models.map(model => [
                model.channelName + " · " + model.modelName, model.judgedAnswers, percent(model.mentionRate), percent(model.recommendRate),
                percent(model.firstChoiceRate), percent(model.brandPositiveRate), percent(model.recommendShare)]))));
        panel.append(section("主要发现与优先行动", null, element("p", "report-empty", "待接入：将基于汇总与证据生成。")));
    }

    function renderVisibility(panel, summary) {
        panel.append(section("可见度（非品牌问题）", "问题覆盖率：至少有一次提到目标的题数占比；稳定性：同一题多次采样中提及结果一致的题数占比。",
            table(["模型", "非品牌回答", "提及率", "推荐率", "首推率", "平均排名", "问题覆盖率", "稳定性"], summary.models.map(model => [
                model.channelName + " · " + model.modelName, model.nonBrandAnswers, percent(model.mentionRate), percent(model.recommendRate),
                percent(model.firstChoiceRate), model.averageRank ?? "—", percent(model.questionCoverage), percent(model.stability)]))));
        const strengthKeys = Object.keys(strengthLabels);
        panel.append(section("推荐强度（提到目标时）", "提到不等于推荐得好：顺带一提、附带缺点都说明推荐不够坚定。",
            table(["模型", ...strengthKeys.map(key => strengthLabels[key])], summary.strength.map(row => [
                row.channelName, ...strengthKeys.map(key => row.distribution[key] || 0)]))));
        const lists = element("div", "report-dimensions");
        lists.append(dimensionList("目标的保留意见", "被推荐时附带的缺点，是要补的短板", summary.reservations),
            dimensionList("用户选择标准", "未提到目标的回答强调了什么", summary.criteria),
            dimensionList("用户顾虑", "未提到目标的回答提醒了什么", summary.concerns));
        panel.append(section("推荐理由与用户关注点", "按归一后的维度统计次数，附回答原文。", lists));
        panel.append(section("按问题意图拆分（非品牌问题）", null, table(["问题意图", "模型", "回答数", "提及率", "推荐率"], summary.byIntent.map(row => [
            intentLabels[row.intent] || row.intent, row.channelName, row.answers, percent(row.mentionRate), percent(row.recommendRate)]), 2)));
    }

    function dimensionList(title, help, rows) {
        const box = element("div", "report-dimension-list");
        box.append(element("h4", null, title), element("p", "help", help));
        if (!rows.length) box.append(element("p", "report-empty", "暂无"));
        for (const row of rows) {
            const item = element("div", "report-dimension");
            item.append(element("strong", null, row.dimension), element("span", "count", row.count + " 次"));
            for (const example of row.examples) {
                const quote = element("p", "report-quote", "“" + example.quote + "” — " + example.channelName);
                const open = element("button", "text-button", "看回答"); open.type = "button";
                open.addEventListener("click", () => openAnswer(example.resultId));
                quote.append(open); item.append(quote);
            }
            box.append(item);
        }
        return box;
    }

    async function openAnswer(resultId) {
        show("answer-error", "");
        byId("answer-content").textContent = "正在加载…";
        answerDialog.showModal();
        try {
            const answer = await request("/diagnosis-tasks/" + report.taskId + "/results/" + resultId);
            byId("answer-note").textContent = (answer.channelName || "—") + " · 问题：" + (answer.questionText || "—");
            byId("answer-content").textContent = answer.answerText || "无回答正文";
        } catch (error) { byId("answer-content").textContent = ""; show("answer-error", error.message); }
    }

    byId("report-filters").addEventListener("submit", event => { event.preventDefault(); page = 0; loadList().catch(error => show("report-error", error.message)); });
    byId("report-previous").addEventListener("click", () => { page--; loadList().catch(error => show("report-error", error.message)); });
    byId("report-next").addEventListener("click", () => { page++; loadList().catch(error => show("report-error", error.message)); });
    byId("new-report").addEventListener("click", () => openCreate(null).catch(error => show("create-error", error.message)));
    byId("close-create").addEventListener("click", () => createDialog.close());
    byId("cancel-create").addEventListener("click", () => createDialog.close());
    byId("close-detail").addEventListener("click", () => { clearTimeout(detailTimer); detailDialog.close(); });
    byId("close-answer").addEventListener("click", () => answerDialog.close());
    byId("create-report-form").addEventListener("submit", async event => {
        event.preventDefault();
        if (creating || !byId("create-task").value) return;
        creating = true; byId("submit-create").disabled = true; show("create-error", "");
        try {
            const created = await request("/diagnosis-reports", {method:"POST", body:JSON.stringify({taskId:Number(byId("create-task").value)})});
            createDialog.close();
            show("report-success", "已开始生成报告 #" + created.id + "，共 " + created.progress.total + " 条有效回答待判定。");
            page = 0; await loadList();
            await openDetail(created.id);
        } catch (error) { show("create-error", error.message); }
        finally { creating = false; byId("submit-create").disabled = false; }
    });

    // 从诊断任务详情跳转时带 taskId，直接打开生成对话框并选中该任务
    const preselected = new URLSearchParams(location.search).get("taskId");
    loadList().then(() => preselected ? openCreate(preselected) : null).catch(error => show("report-error", error.message));
})();
