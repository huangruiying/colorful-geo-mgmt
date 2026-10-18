"use strict";
(() => {
    const byId = id => document.getElementById(id);
    const createDialog = byId("create-report-dialog"), detailDialog = byId("report-detail-dialog"), answerDialog = byId("answer-dialog");
    const labels = {PENDING:"待生成", RUNNING:"生成中", SUCCEEDED:"已完成", FAILED:"失败"};
    const strengthLabels = {FIRST_CHOICE:"首推", STRONG:"强推", PARALLEL:"并列", PASSING:"顺带一提", WITH_RESERVATION:"附带缺点", NOT_RECOMMENDED:"不推荐"};
    const intentLabels = {RECOMMENDATION:"推荐选择", PROBLEM_SOLVING:"解决问题", COMPARISON:"对比", PURCHASE:"购买决策", TECHNICAL:"技术细节", AWARENESS:"了解认知"};
    const sourceTypeLabels = {OWNED:"官网", PUBLISHED:"我方发布"};
    // 子 Tab 与诊断报告全景一致；①–⑤ 已接入，⑥ 优化建议尚无生成逻辑
    const tabs = [["overview", "① 诊断概览"], ["visibility", "② 可见度与推荐"], ["brand", "③ 品牌认知"], ["competitor", "④ 竞品对比"], ["source", "⑤ 引用与内容"], ["advice", "⑥ 优化建议"]];
    const readyTabs = ["overview", "visibility", "brand", "competitor", "source"];
    let page = 0, reportId = null, answerId = null;
    let report = null, activeTab = "overview", creating = false;
    const request = window.AdminHttp.requestJson;
    const answerRequests = window.AdminAsync.createRequestScope();
    const createRequests = window.AdminAsync.createRequestScope();
    const active = status => status === "PENDING" || status === "RUNNING";
    const time = value => value?.replace("T", " ") || "—";
    const percent = value => value == null ? "—" : (value * 100).toFixed(1) + "%";
    const show = (id, text) => { byId(id).textContent = text; byId(id).hidden = !text; };
    const listPoller = window.AdminAsync.createPoller(readReportList, error => show("report-error", error.message));
    const detailPoller = window.AdminAsync.createPoller(readReportDetail, error => show("detail-error", error.message));
    const loadList = listPoller.refresh;
    const element = (tag, className, text) => {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    };
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

    /** 当前筛选页的数据才可写入列表；生成中的报告继续刷新，失败不再静默停止。 */
    async function readReportList(ticket) {
        const query = new URLSearchParams({page, size:10});
        if (byId("filter-status").value) query.set("status", byId("filter-status").value);
        const result = await request("/diagnosis-reports?" + query, {signal:ticket.signal});
        if (!ticket.isCurrent()) return false;
        show("report-error", "");
        const rows = byId("report-list"); rows.replaceChildren();
        if (!result.items.length) {
            const cell = rows.insertRow().insertCell(); cell.colSpan = 6; cell.className = "table-message";
            cell.textContent = "暂无诊断报告，点击「生成诊断报告」选择一次已结束的诊断任务。";
        }
        for (const item of result.items) {
            const row = rows.insertRow();
            row.insertCell().textContent = "#" + item.id;
            const name = row.insertCell();
            name.append(element("span", null, (item.planName || "—") + " / " + (item.projectName || "—")), element("span", "task-secondary", "诊断任务 #" + item.diagnosisTaskId));
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
        return result.items.some(item => active(item.status));
    }

    /** 读取全部候选任务；关闭窗口或再次打开时，旧候选请求不再回填。 */
    const openCreate = preselectedTaskId => createRequests.run(async ticket => {
        show("create-error", "");
        const select = byId("create-task");
        select.disabled = true; byId("submit-create").disabled = true;
        select.replaceChildren(new Option("正在加载…", ""));
        if (!createDialog.open) createDialog.showModal();
        document.body.classList.add("task-dialog-open");
        const items = await window.AdminPagination.listAll("/diagnosis-tasks", {signal:ticket.signal});
        if (!ticket.isCurrent() || !createDialog.open) return;
        // 只有已结束且有成功回答的任务才能生成报告，与服务端校验一致
        const tasks = items.filter(task => task.status === "SUCCEEDED" || task.status === "PARTIAL_FAILED");
        select.replaceChildren(new Option(tasks.length ? "请选择诊断任务" : "暂无可生成报告的诊断任务", ""));
        for (const task of tasks) {
            select.add(new Option("#" + task.id + " · " + (task.planName || "—") + " · " + (task.projectName || "—")
                + " · 成功 " + task.progress.succeeded + " 条 · " + time(task.createdAt), task.id));
        }
        if (preselectedTaskId && tasks.some(task => String(task.id) === preselectedTaskId)) select.value = preselectedTaskId;
        select.disabled = false; byId("submit-create").disabled = !tasks.length;
    });

    /** 打开指定报告，先取消旧详情与回答，避免不同报告之间共享未完成读取。 */
    async function openDetail(id) {
        detailPoller.stop(); answerRequests.cancel(); answerDialog.close();
        reportId = id; report = null; activeTab = "overview"; show("detail-error", "");
        byId("report-detail-title").textContent = "诊断报告 #" + id;
        byId("report-panel").replaceChildren(element("p", "help", "正在加载…"));
        if (!detailDialog.open) detailDialog.showModal();
        document.body.classList.add("task-dialog-open");
        await detailPoller.refresh();
    }

    /** 只更新仍打开的当前报告；读取完成后由公共轮询按报告状态决定是否继续。 */
    async function readReportDetail(ticket) {
        if (!detailDialog.open) return false;
        const id = reportId;
        const detail = await request("/diagnosis-reports/" + id, {signal:ticket.signal});
        if (!ticket.isCurrent() || reportId !== id || !detailDialog.open) return false;
        show("detail-error", "");
        report = detail;
        const judge = detail.judgeChannel ? (detail.judgeChannel.channelName || "—") + " · " + (detail.judgeChannel.modelName || "—") : "未开始";
        byId("report-detail-note").textContent = (detail.planName || "—") + " / " + (detail.projectName || "—") + " · 诊断任务 #" + detail.diagnosisTaskId
            + " · 判定模型：" + judge + " · " + (labels[detail.status] || detail.status);
        renderTabs();
        renderPanel();
        return active(detail.status);
    }

    function renderTabs() {
        const container = byId("report-tabs"); container.replaceChildren();
        for (const [key, title] of tabs) {
            const tab = element("button", "report-tab", title); tab.type = "button"; tab.setAttribute("role", "tab");
            tab.setAttribute("aria-selected", String(key === activeTab));
            tab.dataset.pending = String(!readyTabs.includes(key));
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
        const summary = report.summary;
        if (activeTab === "overview") renderOverview(panel, summary);
        else if (activeTab === "visibility") renderVisibility(panel, summary);
        else if (activeTab === "advice") panel.append(element("div", "report-progress", "本部分待接入：优化建议需要基于 ②–⑤ 的结果另行生成，尚未实现。"));
        // 汇总在报告完成时冻结，③–⑤ 上线前生成的报告没有对应数据，不重算旧报告
        else if (!summary[activeTab]) panel.append(element("div", "report-progress", "该报告生成于此部分上线之前，没有对应数据；重新生成报告后可查看。"));
        else if (activeTab === "brand") renderBrand(panel, summary.brand);
        else if (activeTab === "competitor") renderCompetitor(panel, summary.competitor);
        else renderSource(panel, summary.source);
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

    /** ③ 品牌认知：只看点名问题，模型怎么评价目标、描述对不对。 */
    function renderBrand(panel, brand) {
        panel.append(section("各模型评价（点名问题）", "情感按回答计数；描述准确率只统计判定给出了对错的回答，依据是项目资料。",
            table(["模型", "点名回答", "正面", "中性", "负面", "描述准确率"], brand.models.map(model => [
                model.channelName + " · " + model.modelName, model.brandAnswers, model.positive, model.neutral, model.negative, percent(model.accurateRate)]))));
        const lists = element("div", "report-dimensions");
        lists.append(dimensionList("正面点", "继续强化的优势", brand.positives),
            dimensionList("负面点", "需要回应或纠正", brand.negatives),
            dimensionList("提到的卖点", "模型已经知道的核心卖点", brand.sellingPoints));
        panel.append(section("正面点、负面点与卖点", "按归一后的维度统计次数，附回答原文。", lists));
        panel.append(section("错误信息清单", "与项目资料不符的描述，资料没写的内容不判对错。", quoteList(brand.factErrors.map(error => ({
            title:error.content, quote:error.quote, channelName:error.channelName, resultId:error.resultId})))));
    }

    /** ④ 竞品对比：目标与预设竞品同口径比较，再看对比问题里各维度谁赢。 */
    function renderCompetitor(panel, competitor) {
        panel.append(section("目标与竞品（非品牌问题）", "与 ② 的口径相同，分母为该模型的非品牌回答；对比问题点了竞品的名，不计入提及率。",
            table(["模型", "品牌", "提及率", "推荐率", "首推率"], competitor.brands.map(row => [
                row.channelName + " · " + row.modelName, row.brandName + (row.target ? "（目标）" : ""),
                percent(row.mentionRate), percent(row.recommendRate), percent(row.firstChoiceRate)]), 2)));
        const lists = element("div", "report-dimensions");
        lists.append(dimensionList("分维度胜负（对比问题）", "目标相对竞品在各维度上的结果", competitor.dimensions,
                row => "赢 " + row.win + " · 输 " + row.lose + " · 平 " + row.tie),
            dimensionList("竞品被推荐的理由", "只取回答里明确写出的理由", competitor.competitorReasons));
        panel.append(section("对比维度与竞品理由", "按归一后的维度统计，附回答原文。", lists));
        panel.append(section("新出现的同类产品", "不在预设竞品里、回答中出现过的产品，可考虑加入竞品。",
            table(["产品", "出现回答数", "被推荐回答数"], competitor.candidates.map(row => [row.name, row.mentions, row.recommended]))));
    }

    /** ⑤ 引用与内容：模型引用了哪些来源，官网和我方发布的内容有没有被用上。 */
    function renderSource(panel, source) {
        panel.append(section("各模型来源情况", "来源覆盖率 = 带来源回答 ÷ 全部回答；各类引用率的分母为带来源回答。各平台“引用”含义不同，只在同类平台之间比较。",
            table(["模型", "回答", "带来源", "来源覆盖率", "引用官网", "引用我方发布", "引用提到目标的第三方", "引用提到竞品的内容"], source.models.map(model => [
                model.channelName + " · " + model.modelName, model.answers, model.withSources, percent(model.sourceCoverage),
                percent(model.ownedRate), percent(model.publishedRate), percent(model.thirdPartyRate), percent(model.competitorRate)]))));
        panel.append(section("各模型常引用的平台", "按被引用次数排序，每个模型最多 10 个。", table(["模型", "平台", "引用次数"],
            source.platforms.flatMap(row => row.platforms.map(platform => [row.channelName, platform.platform, platform.count])), 2)));
        panel.append(section("被引用的我方链接", "官网域名下的页面，以及与发布记录一致的链接；按生成报告时的发布记录判断。",
            table(["链接", "标题", "类型", "引用回答数"], source.ownLinks.map(link => [link.url, link.title || "—", sourceTypeLabels[link.sourceType] || link.sourceType, link.count]), 3)));
    }

    /** 维度排行：describe 决定每行右侧的计数写法，示例中有品牌时一并展示。 */
    function dimensionList(title, help, rows, describe = row => row.count + " 次") {
        const box = element("div", "report-dimension-list");
        box.append(element("h4", null, title), element("p", "help", help));
        if (!rows.length) box.append(element("p", "report-empty", "暂无"));
        for (const row of rows) {
            const item = element("div", "report-dimension");
            item.append(element("strong", null, row.dimension), element("span", "count", describe(row)));
            for (const example of row.examples) item.append(quoteLine(example.quote, example.channelName + (example.brand ? " · " + example.brand : ""), example.resultId));
            box.append(item);
        }
        return box;
    }

    /** 逐条清单（如错误信息）：每条一个标题加一句原文。 */
    function quoteList(rows) {
        const box = element("div", "report-dimension-list");
        if (!rows.length) box.append(element("p", "report-empty", "暂无"));
        for (const row of rows) {
            const item = element("div", "report-dimension");
            item.append(element("strong", null, row.title || "—"), quoteLine(row.quote, row.channelName, row.resultId));
            box.append(item);
        }
        return box;
    }

    /** 一句原文加「看回答」按钮，点开对应的完整回答。 */
    function quoteLine(quote, from, resultId) {
        const line = element("p", "report-quote", "“" + quote + "” — " + from);
        const open = element("button", "text-button", "看回答"); open.type = "button";
        open.addEventListener("click", () => openAnswer(resultId));
        line.append(open);
        return line;
    }

    /** 读取报告对应的完整回答，正文使用 Markdown，问题与错误提示保持原文。 */
    const openAnswer = resultId => answerRequests.run(async ticket => {
        const owner = reportId, taskId = report.diagnosisTaskId;
        answerId = resultId;
        show("answer-error", "");
        byId("answer-content").textContent = "正在加载…";
        byId("answer-note").textContent = "";
        if (!answerDialog.open) answerDialog.showModal();
        try {
            const answer = await request("/diagnosis-tasks/" + taskId + "/results/" + resultId, {signal:ticket.signal});
            if (!ticket.isCurrent() || reportId !== owner || !detailDialog.open || !answerDialog.open) return;
            byId("answer-note").textContent = (answer.channelName || "—") + " · 问题：" + (answer.questionText || "—");
            MarkdownView.render(byId("answer-content"), answer.answerText || "无回答正文");
        } catch (error) {
            if (ticket.isCurrent() && answerDialog.open && reportId === owner) {
                byId("answer-content").textContent = ""; show("answer-error", error.message);
            }
        }
    });

    byId("report-filters").addEventListener("submit", event => { event.preventDefault(); page = 0; loadList().catch(error => show("report-error", error.message)); });
    byId("report-previous").addEventListener("click", () => { page--; loadList().catch(error => show("report-error", error.message)); });
    byId("report-next").addEventListener("click", () => { page++; loadList().catch(error => show("report-error", error.message)); });
    byId("new-report").addEventListener("click", () => openCreate(null).catch(error => show("create-error", error.message)));
    byId("close-create").addEventListener("click", () => { if (!creating) createDialog.close(); });
    byId("cancel-create").addEventListener("click", () => { if (!creating) createDialog.close(); });
    byId("close-detail").addEventListener("click", () => detailDialog.close());
    byId("close-answer").addEventListener("click", () => answerDialog.close());
    window.AdminDialogs.bind([createDialog, detailDialog, answerDialog], {
        canClose:dialog => dialog !== createDialog || !creating,
        onClose:dialog => {
            if (dialog === createDialog) createRequests.cancel();
            if (dialog === detailDialog) { detailPoller.stop(); answerRequests.cancel(); answerDialog.close(); reportId = null; report = null; }
            if (dialog === answerDialog) { answerRequests.cancel(); answerId = null; }
        }
    });
    byId("create-report-form").addEventListener("submit", async event => {
        event.preventDefault();
        if (creating || !byId("create-task").value) return;
        creating = true; byId("submit-create").disabled = true; show("create-error", "");
        try {
            const created = await request("/diagnosis-reports/diagnosis-tasks/" + Number(byId("create-task").value), {method:"POST"});
            createDialog.close();
            show("report-success", "已开始生成报告 #" + created.id + "，共 " + created.progress.total + " 条有效回答待判定。");
            page = 0; await loadList();
            await openDetail(created.id);
        } catch (error) { show("create-error", error.message); }
        finally { creating = false; byId("submit-create").disabled = false; }
    });

    // 从诊断任务详情跳转时带 diagnosisTaskId，直接打开生成对话框并选中该任务
    const preselected = new URLSearchParams(location.search).get("diagnosisTaskId");
    window.addEventListener("pagehide", () => { listPoller.stop(); detailPoller.stop(); answerRequests.cancel(); createRequests.cancel(); });
    // 后退恢复时为仍打开的窗口重新读取；取消等待没有终止服务端生成。
    window.addEventListener("pageshow", event => {
        if (!event.persisted) return;
        loadList();
        if (detailDialog.open) detailPoller.refresh();
        if (answerDialog.open && answerId != null) openAnswer(answerId);
        if (createDialog.open) openCreate(byId("create-task").value).catch(error => show("create-error", error.message));
    });
    loadList().then(() => preselected ? openCreate(preselected) : null).catch(error => show("report-error", error.message));
})();
