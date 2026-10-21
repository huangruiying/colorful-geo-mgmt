"use strict";
(() => {
    const byId = id => document.getElementById(id);
    const createDialog = byId("create-report-dialog"), detailDialog = byId("report-detail-dialog"), answerDialog = byId("answer-dialog");
    const evidenceDialog = byId("evidence-dialog");
    const labels = {PENDING:"待生成", RUNNING:"生成中", SUCCEEDED:"已完成", FAILED:"失败"};
    const strengthLabels = {FIRST_CHOICE:"首推", STRONG:"强推", PARALLEL:"并列", PASSING:"顺带一提", WITH_RESERVATION:"附带缺点", NOT_RECOMMENDED:"不推荐"};
    const intentLabels = {RECOMMENDATION:"推荐选择", PROBLEM_SOLVING:"解决问题", COMPARISON:"对比", PURCHASE:"购买决策", TECHNICAL:"技术细节", AWARENESS:"了解认知"};
    const sourceTypeLabels = {OWNED:"官网", PUBLISHED:"我方发布", THIRD_PARTY_TARGET:"提到目标的第三方", COMPETITOR:"提到竞品", OTHER:"其他"};
    const categoryLabels = {FILL_CONTENT_GAP:"补内容空缺", IMPROVE_CONTENT:"完善已有内容", ADD_EVIDENCE:"增加可信证据",
        CORRECT_INFORMATION:"纠正和统一信息", IMPROVE_ACCESS:"改善公开访问与检索", ADD_THIRD_PARTY:"补充第三方覆盖"};
    const evidenceStatusLabels = {ANSWER_STATED:"回答明确表达", INFERRED:"待验证推测"};
    // 子 Tab 与诊断报告全景一致，六个页签均已接入
    const tabs = [["overview", "① 诊断概览"], ["visibility", "② 可见度与推荐"], ["brand", "③ 品牌认知"], ["competitor", "④ 竞品对比"], ["source", "⑤ 引用与内容"], ["advice", "⑥ 优化建议"]];
    let page = 0, reportId = null, answerId = null;
    let report = null, activeTab = "overview", creating = false;
    // 正在补全的报告 ID：只防同一份报告重复补全，不同报告可以同时补全
    const supplementingIds = new Set();
    // 按模型筛选：空字符串为全部模型；只作用于按模型分行的表格，排行与清单是全部模型合计
    let modelFilter = "";
    const ofModel = rows => modelFilter ? rows.filter(row => String(row.channelId) === modelFilter) : rows;
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
        reportId = id; report = null; activeTab = "overview"; modelFilter = ""; show("detail-error", "");
        byId("supplement-report").hidden = true;
        byId("export-report-html").hidden = true;
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
        byId("report-detail-note").textContent = reportNote(detail);
        renderModelFilter();
        renderSupplementButton();
        byId("export-report-html").hidden = detail.status !== "SUCCEEDED";
        renderTabs();
        renderPanel();
        return active(detail.status);
    }

    /** @return 报告所属方案、项目、诊断任务、判定模型与状态的一行说明，详情抬头与导出网页抬头共用 */
    function reportNote(detail) {
        const judge = detail.judgeChannel ? (detail.judgeChannel.channelName || "—") + " · " + (detail.judgeChannel.modelName || "—") : "未开始";
        return (detail.planName || "—") + " / " + (detail.projectName || "—") + " · 诊断任务 #" + detail.diagnosisTaskId
            + " · 判定模型：" + judge + " · " + (labels[detail.status] || detail.status);
    }

    /** 报告完成且有两个以上模型时才显示模型筛选，选项来自汇总中的模型。 */
    function renderModelFilter() {
        const select = byId("report-model-filter");
        const models = report.status === "SUCCEEDED" ? (report.summary.source?.models || report.summary.models) : [];
        select.hidden = models.length < 2;
        select.replaceChildren(new Option("全部模型", ""), ...models.map(model => new Option(model.channelName + " · " + model.modelName, model.channelId)));
        select.value = modelFilter;
    }

    function renderTabs() {
        const container = byId("report-tabs"); container.replaceChildren();
        for (const [key, title] of tabs) {
            const tab = element("button", "report-tab", title); tab.type = "button"; tab.setAttribute("role", "tab");
            tab.setAttribute("aria-selected", String(key === activeTab));
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
        if (modelFilter) panel.append(modelFilterNote());
        renderTabContent(panel, activeTab, report.summary);
    }

    /** 已选模型时的筛选说明，提醒哪些表格只含该模型、哪些仍是全部模型合计。 */
    function modelFilterNote() {
        const model = byId("report-model-filter").selectedOptions[0]?.textContent;
        return element("p", "report-filter-note", "已筛选模型：" + model + "。按模型分行的表格只显示该模型；维度排行、链接与清单为全部模型合计。");
    }

    /**
     * 渲染一个页签的内容；页面切换页签与导出网页逐个输出页签都走这里，保证两边内容一致。
     * @param {HTMLElement} panel 内容写入的容器
     * @param {string} key 页签标识，取自 tabs
     * @param {object} summary 已完成报告的冻结汇总
     */
    function renderTabContent(panel, key, summary) {
        if (key === "overview") renderOverview(panel, summary);
        else if (key === "visibility") renderVisibility(panel, summary);
        // 页签内容缺失时提示用右上角「补全报告」补全
        else if (!summary[key]) panel.append(supplementNotice());
        else if (key === "brand") renderBrand(panel, summary.brand);
        else if (key === "competitor") renderCompetitor(panel, summary.competitor);
        else if (key === "source") renderSource(panel, summary.source);
        else renderAdvice(panel, summary);
    }

    /**
     * 把已完成报告的六个页签按顺序展开，导出为可离线打开的独立网页。
     * 内容与页面当前看到的一致：沿用模型筛选；证据与回答原文需在系统内点开查看，不进入导出网页。
     */
    function exportReportHtml() {
        // 1. 抬头：报告编号、所属任务与判定模型、导出时间，有模型筛选时一并注明
        const documentNode = element("article", "report-export");
        documentNode.append(element("h1", null, "诊断报告 #" + report.id), element("p", "report-export-note", reportNote(report)),
            element("p", "report-export-note", "导出时间：" + window.AdminFormat.dateTime(new Date())));
        if (modelFilter) documentNode.append(modelFilterNote());
        // 导出网页无法补全，缺失内容在抬头提醒先补全再导出
        if (report.missingParts?.length) documentNode.append(element("p", "report-filter-note", "本报告有缺失内容（见各部分提示），建议先在系统中点击「补全报告」，完成后再导出。"));
        // 2. 六个页签依次展开，各自成一部分
        for (const [key, title] of tabs) {
            const part = element("section", "report-export-part");
            part.append(element("h2", null, title));
            renderTabContent(part, key, report.summary);
            documentNode.append(part);
        }
        // 3. 交给公共导出模块，文件名带方案名与报告编号，便于区分多份报告
        window.AdminHtmlExport.downloadPage(documentNode, {
            title:"诊断报告-" + (report.planName || "未命名方案") + "-" + report.id, bodyClass:"diagnosis-page"});
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
            table(["模型", "判定回答", "提及率", "推荐率", "首推率", "品牌正面率", "推荐份额"], ofModel(summary.models).map(model => [
                model.channelName + " · " + model.modelName, model.judgedAnswers, percent(model.mentionRate), percent(model.recommendRate),
                percent(model.firstChoiceRate), percent(model.brandPositiveRate), percent(model.recommendShare)]))));
        panel.append(section("主要发现与优先行动", "由模型根据规则发现的问题归纳，每条都可以点「看证据」核对；完整内容见 ⑥ 优化建议。", renderKeyFindings(summary)));
    }

    function renderVisibility(panel, summary) {
        panel.append(section("可见度（非品牌问题）", "问题覆盖率：至少有一次提到目标的题数占比；稳定性：同一题多次采样中提及结果一致的题数占比。",
            table(["模型", "非品牌回答", "提及率", "推荐率", "首推率", "平均排名", "问题覆盖率", "稳定性"], ofModel(summary.models).map(model => [
                model.channelName + " · " + model.modelName, model.nonBrandAnswers, percent(model.mentionRate), percent(model.recommendRate),
                percent(model.firstChoiceRate), model.averageRank ?? "—", percent(model.questionCoverage), percent(model.stability)]))));
        const strengthKeys = Object.keys(strengthLabels);
        panel.append(section("推荐强度（非品牌问题中提到目标时）", "提到不等于推荐得好：顺带一提、附带缺点都说明推荐不够坚定。点名与对比问题必然提到目标，不计入。",
            table(["模型", ...strengthKeys.map(key => strengthLabels[key])], ofModel(summary.strength).map(row => [
                row.channelName, ...strengthKeys.map(key => row.distribution[key] || 0)]))));
        const lists = element("div", "report-dimensions");
        lists.append(dimensionList("目标的保留意见", "被推荐时附带的缺点，是要补的短板", summary.reservations),
            dimensionList("用户选择标准", "未提到目标的回答强调了什么", summary.criteria),
            dimensionList("用户顾虑", "未提到目标的回答提醒了什么", summary.concerns));
        panel.append(section("推荐理由与用户关注点", "按归一后的维度统计次数，附回答原文。", lists));
    }

    /** ③ 品牌认知：只看点名问题，模型怎么评价目标、描述对不对、负面来自哪里、卖点说没说到。 */
    function renderBrand(panel, brand) {
        panel.append(section("各模型评价（点名问题）", "情感按回答计数；描述准确率只统计判定给出了对错的回答，依据是项目资料；卖点覆盖率 = 至少说到一个核心卖点的回答占比。",
            table(["模型", "点名回答", "正面", "中性", "负面", "描述准确率", "卖点覆盖率"], ofModel(brand.models).map(model => [
                model.channelName + " · " + model.modelName, model.brandAnswers, model.positive, model.neutral, model.negative,
                percent(model.accurateRate), percent(model.sellingPointRate)]))));
        const lists = element("div", "report-dimensions");
        lists.append(dimensionList("正面点", "继续强化的优势", brand.positives),
            dimensionList("负面点", "需要回应或纠正", brand.negatives),
            dimensionList("提到的卖点", "模型已经知道的核心卖点", brand.sellingPoints));
        panel.append(section("正面点、负面点与卖点", "按归一后的维度统计次数，附回答原文。", lists));
        panel.append(section("负面来源", "判定为负面的点名回答引用了哪些平台，决定去哪里处理。", table(["平台", "引用次数", "链接示例"],
            brand.negativeSources.map(row => [row.platform, row.count, row.links.map(link => link.title || link.url).join("；")]), 1)));
        panel.append(section("错误信息清单", "与项目资料不符的描述，资料没写的内容不判对错。", quoteList(ofModel(brand.factErrors).map(error => ({
            title:error.content, quote:error.quote, channelName:error.channelName, resultId:error.resultId})))));
    }

    /** ④ 竞品对比：目标与预设竞品同口径比较，再看同题先后、竞品强在哪些场景、对比维度谁赢。 */
    function renderCompetitor(panel, competitor) {
        panel.append(section("目标与竞品（非品牌问题）", "与 ② 的口径相同，分母为该模型的非品牌回答；对比问题点了竞品的名，不计入提及率。",
            table(["模型", "品牌", "提及率", "推荐率", "首推率"], ofModel(competitor.brands).map(row => [
                row.channelName + " · " + row.modelName, row.brandName + (row.target ? "（目标）" : ""),
                percent(row.mentionRate), percent(row.recommendRate), percent(row.firstChoiceRate)]), 2)));
        panel.append(section("同题胜率", "同一条回答里目标和竞品都被推荐且都有排名时，目标排在前面的占比。",
            table(["模型", "竞品", "可比较的回答", "目标在前", "竞品在前", "同题胜率"], ofModel(competitor.headToHead).map(row => [
                row.channelName + " · " + row.modelName, row.competitor, row.sameAnswers, row.targetAhead, row.competitorAhead, percent(row.winRate)]), 2)));
        panel.append(section("竞品的优势场景", "按问题意图拆分非品牌问题，只列出竞品推荐率高于目标的组合。",
            table(["模型", "问题意图", "竞品", "回答数", "目标推荐率", "竞品推荐率"], ofModel(competitor.advantageScenes).map(row => [
                row.channelName, intentLabels[row.intent] || row.intent, row.competitor, row.answers,
                percent(row.targetRecommendRate), percent(row.competitorRecommendRate)]), 3)));
        const lists = element("div", "report-dimensions");
        lists.append(dimensionList("分维度胜负（对比问题）", "目标相对竞品在各维度上的结果", competitor.dimensions,
                row => "赢 " + row.win + " · 输 " + row.lose + " · 平 " + row.tie),
            dimensionList("竞品被推荐的理由", "只取回答里明确写出的理由", competitor.competitorReasons));
        panel.append(section("对比维度与竞品理由", "按归一后的维度统计，附回答原文。", lists));
        panel.append(section("新出现的同类产品", "不在预设竞品里、回答中出现过的产品，可考虑加入竞品。",
            table(["产品", "出现回答数", "被推荐回答数"], competitor.candidates.map(row => [row.name, row.mentions, row.recommended]))));
    }

    /** ⑤ 引用与内容：模型引用了哪些来源，我方内容有没有被用上，竞品靠哪些内容，用户关心的方向有没有我方内容。 */
    function renderSource(panel, source) {
        panel.append(section("各模型来源情况", "来源覆盖率 = 带来源回答 ÷ 全部回答；各类引用率的分母为带来源回答。各平台“引用”含义不同，只在同类平台之间比较。",
            table(["模型", "回答", "带来源", "来源覆盖率", "引用官网", "引用我方发布", "引用提到目标的第三方", "引用提到竞品的内容"], ofModel(source.models).map(model => [
                model.channelName + " · " + model.modelName, model.answers, model.withSources, percent(model.sourceCoverage),
                percent(model.ownedRate), percent(model.publishedRate), percent(model.thirdPartyRate), percent(model.competitorRate)]))));
        panel.append(section("各模型常引用的平台", "按被引用次数排序，每个模型最多 10 个。", table(["模型", "平台", "引用次数"],
            ofModel(source.platforms).flatMap(row => row.platforms.map(platform => [row.channelName, platform.platform, platform.count])), 2)));
        panel.append(section("被引用的我方链接", "官网域名下的页面，以及与发布记录一致的链接；按生成报告时的发布记录判断。",
            table(["链接", "标题", "类型", "引用回答数"], source.ownLinks.map(link => [link.url, link.title || "—", sourceTypeLabels[link.sourceType] || link.sourceType, link.count]), 3)));
        panel.append(section("竞品的来源", "推荐了预设竞品的回答引用了哪些内容，看竞品被推荐靠的是什么。",
            table(["链接", "标题", "平台", "类型", "引用回答数"], source.competitorLinks.map(link => [link.url, link.title || "—", link.platform || "—",
                sourceTypeLabels[link.sourceType] || link.sourceType, link.count]), 4)));
        const gaps = element("div", "report-dimensions");
        gaps.append(dimensionList("用户关心的方向", "引用我方为 0 的方向，说明我方内容没被用上（可能没有、不公开或不好找），需要人工确认",
            source.contentGaps, row => "提到 " + row.answers + " 条 · 引用我方 " + row.ownCitedAnswers + " 条"));
        panel.append(section("内容差距", "非品牌问题中回答强调的选择标准和用户顾虑，相关回答有没有引用官网或我方发布内容。", gaps));
    }

    /** ① 概览中的主要发现与前三条建议，内容来自 summary.advice。 */
    function renderKeyFindings(summary) {
        const advice = summary.advice;
        if (!summary.issues || !summary.advice) return supplementNotice();
        if (advice.status === "NO_ISSUES") return element("p", "report-empty", "规则没有发现需要处理的问题。");
        if (advice.status === "FAILED") return element("p", "report-empty", advice.errorMessage + "，见 ⑥ 优化建议。");
        const box = element("div", "report-dimension-list");
        box.append(element("h4", null, "主要发现"));
        for (const finding of advice.findings) box.append(adviceItem(finding.content, null, null, finding.issueIds));
        box.append(element("h4", null, "优先行动"));
        for (const action of advice.actions.slice(0, 3)) box.append(adviceItem(action.title, categoryLabels[action.category], action.content, action.issueIds));
        return box;
    }

    /** ⑥ 优化建议：模型写的建议与主要发现，以及规则发现的全部问题；每条都能打开证据。 */
    function renderAdvice(panel, summary) {
        const advice = summary.advice;
        if (advice.status === "SUCCEEDED") {
            const actions = element("div", "report-dimension-list");
            if (!advice.actions.length) actions.append(element("p", "report-empty", "暂无"));
            advice.actions.forEach((action, index) => actions.append(adviceItem((index + 1) + ". " + action.title,
                categoryLabels[action.category] + " · " + evidenceStatusLabels[action.evidenceStatus], action.content, action.issueIds)));
            panel.append(section("优化建议（按优先级）", "由模型根据下方规则发现的问题归纳；“待验证推测”表示原因或做法是推断，需要先核实。", actions));
            const findings = element("div", "report-dimension-list");
            if (!advice.findings.length) findings.append(element("p", "report-empty", "暂无"));
            for (const finding of advice.findings) findings.append(adviceItem(finding.content, null, null, finding.issueIds));
            panel.append(section("主要发现", null, findings));
        } else {
            panel.append(element("div", "report-progress", advice.status === "NO_ISSUES" ? "规则没有发现需要处理的问题，未生成建议。" : advice.errorMessage));
        }
        const issues = element("div", "report-dimension-list");
        const visibleIssues = summary.issues.filter(issue => !modelFilter || issue.channelId == null || String(issue.channelId) === modelFilter);
        if (!visibleIssues.length) issues.append(element("p", "report-empty", "暂无"));
        for (const issue of visibleIssues) issues.append(adviceItem(issue.id + " " + issue.title, metricsText(issue.metrics), null, [issue.id]));
        panel.append(section("规则发现的问题", "由程序按固定阈值从 ②–⑤ 的结果中挑出，不经过模型；建议只能引用这里的编号。", issues));
        panel.append(section("复测方式", null, element("p", "help",
            "按建议调整后，用同一诊断方案（原问题、原渠道配置）重新执行诊断任务并生成报告；判定模型与提示词版本一致时，再与本报告对比。")));
    }

    /** 报告内容缺失时页签里的提示，补全入口在详情右上角。 */
    function supplementNotice() {
        return element("div", "report-progress", "报告内容缺失时，点击补全报告可补全");
    }

    /** 报告缺少任何内容（接口返回 missingParts 不为空）就显示「补全报告」；只有当前报告正在补全时才禁用。 */
    function renderSupplementButton() {
        const button = byId("supplement-report");
        button.hidden = !report?.missingParts?.length;
        button.disabled = supplementingIds.has(reportId);
        button.textContent = button.disabled ? "补全中…" : "补全报告";
        button.title = "用已保存的判定结果一次补全缺失内容，不重新判定回答；可能调用模型，需要几十秒";
    }

    /** 补全当前报告：一次补全全部缺失内容；完成时如果已切到别的报告，不用旧结果覆盖当前视图。 */
    async function supplementReport() {
        const owner = reportId;
        if (supplementingIds.has(owner)) return;
        supplementingIds.add(owner); show("detail-error", ""); renderSupplementButton();
        try {
            const supplemented = await request("/diagnosis-reports/" + owner + "/supplement", {method:"POST"});
            if (reportId !== owner || !detailDialog.open) return;
            report = supplemented;
            renderModelFilter(); renderPanel();
        } catch (error) {
            if (reportId === owner) show("detail-error", error.message);
        } finally {
            supplementingIds.delete(owner);
            if (reportId === owner && report) renderSupplementButton();
        }
    }

    /** 建议、发现或问题的一行：标题、补充说明、正文，以及「看证据」。 */
    function adviceItem(title, meta, content, issueIds) {
        const item = element("div", "report-dimension report-advice-item");
        item.append(element("strong", null, title));
        if (meta) item.append(element("span", "report-advice-meta", meta));
        if (content) item.append(element("p", "report-advice-content", content));
        const open = element("button", "text-button", "看证据（" + issueIds.join("、") + "）"); open.type = "button";
        open.addEventListener("click", () => openEvidence(issueIds));
        const line = element("p", "report-quote"); line.append(open); item.append(line);
        return item;
    }

    /** @return 问题的支撑数字，比率按百分比展示 */
    function metricsText(metrics) {
        return metrics.map(metric => metric.label + " " + (metric.rate ? percent(metric.value) : metric.value)).join(" · ");
    }

    /** 证据弹窗：展示所选问题的数字和证据（回答原文或问题），可继续打开完整回答。 */
    function openEvidence(issueIds) {
        const content = byId("evidence-content"); content.replaceChildren();
        for (const issue of report.summary.issues.filter(item => issueIds.includes(item.id))) {
            content.append(element("h4", null, issue.id + " " + issue.title), element("p", "help", metricsText(issue.metrics)));
            if (!issue.evidence.length) content.append(element("p", "report-empty", "此问题由上面的数字支撑，没有对应的回答原文。"));
            for (const item of issue.evidence) {
                const from = item.channelName + (item.brand ? " · " + item.brand : "");
                content.append(item.quote != null ? quoteLine(item.quote, from, item.resultId) : answerLine("问题：" + item.question + " — " + from, item.resultId));
            }
        }
        if (!evidenceDialog.open) evidenceDialog.showModal();
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

    /** 一句回答原文加来源，点「看回答」打开完整回答。 */
    function quoteLine(quote, from, resultId) {
        return answerLine("“" + quote + "” — " + from, resultId);
    }

    /** 一行说明文字加「看回答」按钮，点开对应的完整回答。 */
    function answerLine(text, resultId) {
        const line = element("p", "report-quote", text);
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
    byId("close-evidence").addEventListener("click", () => evidenceDialog.close());
    byId("supplement-report").addEventListener("click", supplementReport);
    byId("export-report-html").addEventListener("click", exportReportHtml);
    byId("report-model-filter").addEventListener("change", event => { modelFilter = event.target.value; renderPanel(); });
    window.AdminDialogs.bind([createDialog, detailDialog, evidenceDialog, answerDialog], {
        canClose:dialog => dialog !== createDialog || !creating,
        onClose:dialog => {
            if (dialog === createDialog) createRequests.cancel();
            if (dialog === detailDialog) { detailPoller.stop(); answerRequests.cancel(); answerDialog.close(); evidenceDialog.close(); reportId = null; report = null; }
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
