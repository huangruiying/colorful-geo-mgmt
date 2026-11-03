"use strict";
/**
 * 诊断报告页：报告列表、生成报告、报告详情（六个页签、按模型筛选、补全）、证据与回答弹窗，以及导出网页按钮。
 * <p>页签内容由 DiagnosisReportView（diagnosis-report-view.js）渲染，导出网页由 DiagnosisReportExport（diagnosis-report-export.js）组装；
 * 本文件负责页面状态、接口读取、弹窗与轮询的生命周期。</p>
 */
(() => {
    const view = window.DiagnosisReportView;
    const byId = id => document.getElementById(id);
    const createDialog = byId("create-report-dialog"), detailDialog = byId("report-detail-dialog"), answerDialog = byId("answer-dialog");
    const evidenceDialog = byId("evidence-dialog");
    // 报告列表每页条数；查询参数与「下一页」是否可用共用
    const PAGE_SIZE = 10;
    let page = 0, reportId = null, answerId = null;
    let report = null, activeTab = "overview", creating = false;
    // 正在补全的报告 ID：只防同一份报告重复补全，不同报告可以同时补全
    const supplementingIds = new Set();
    // 按模型筛选：空字符串为全部模型；只作用于按模型分行的表格，排行与清单是全部模型合计
    let modelFilter = "";
    const request = window.AdminHttp.requestJson;
    const answerRequests = window.AdminAsync.createRequestScope();
    const createRequests = window.AdminAsync.createRequestScope();
    const active = status => status === "PENDING" || status === "RUNNING";
    const time = value => value?.replace("T", " ") || "—";
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
    // 页面上的「看证据」「看回答」是按钮，点开本页的证据、回答弹窗；导出网页另有自己的入口
    const pageEntries = Object.freeze({
        evidence:issueIds => entryButton("看证据（" + issueIds.join("、") + "）", () => openEvidence(issueIds)),
        answer:resultId => entryButton("看回答", () => openAnswer(resultId))
    });

    /**
     * 当前筛选页的数据才可写入列表；生成中的报告继续刷新，失败不再静默停止。
     * @param {{signal:AbortSignal, isCurrent:function():boolean}} ticket 本次读取的归属，过期后不再写入列表
     * @return {Promise<boolean>} 列表中有生成中的报告时为 true，由公共轮询继续刷新
     */
    async function readReportList(ticket) {
        const query = new URLSearchParams({page, size:PAGE_SIZE});
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
            const viewButton = element("button", "text-button", "查看"); viewButton.type = "button";
            viewButton.addEventListener("click", () => openDetail(item.id).catch(error => show("report-error", error.message)));
            row.insertCell().append(viewButton);
        }
        byId("report-page-info").textContent = "第 " + (page + 1) + " 页 · 共 " + result.total + " 份";
        byId("report-previous").disabled = page === 0;
        byId("report-next").disabled = (page + 1) * PAGE_SIZE >= result.total;
        return result.items.some(item => active(item.status));
    }

    /**
     * @param {string} status 报告状态
     * @return {HTMLElement} 带颜色的状态标签
     */
    function statusBadge(status) {
        const span = element("span", "task-status", view.statusLabels[status] || status);
        span.dataset.status = status;
        return span;
    }

    /**
     * @param {{total:number, succeeded:number, failed:number}} progress 判定进度
     * @return {string} 已判定条数 / 总条数，有失败时注明失败条数
     */
    function progressText(progress) {
        const done = progress.succeeded + progress.failed;
        return done + " / " + progress.total + (progress.failed ? "（失败 " + progress.failed + "）" : "");
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

    /**
     * 提交生成报告：成功后关闭窗口、刷新列表并打开新报告；提交中不能重复提交，失败时在窗口内提示。
     * @param {SubmitEvent} event 生成窗口的表单提交事件
     */
    async function submitCreate(event) {
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
    }

    /**
     * 打开指定报告，先取消旧详情与回答，避免不同报告之间共享未完成读取。
     * @param {number} id 报告 ID
     */
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

    /**
     * 只更新仍打开的当前报告；读取完成后由公共轮询按报告状态决定是否继续。
     * @param {{signal:AbortSignal, isCurrent:function():boolean}} ticket 本次读取的归属，过期后不再写入详情
     * @return {Promise<boolean>} 报告仍在生成时为 true，继续轮询
     */
    async function readReportDetail(ticket) {
        if (!detailDialog.open) return false;
        const id = reportId;
        const detail = await request("/diagnosis-reports/" + id, {signal:ticket.signal});
        if (!ticket.isCurrent() || reportId !== id || !detailDialog.open) return false;
        show("detail-error", "");
        report = detail;
        byId("report-detail-note").textContent = view.buildReportNote(detail);
        renderModelFilter();
        renderSupplementButton();
        byId("export-report-html").hidden = detail.status !== "SUCCEEDED";
        renderTabs();
        renderPanel();
        return active(detail.status);
    }

    /** 报告完成且有两个以上模型时才显示模型筛选，选项来自汇总中的模型。 */
    function renderModelFilter() {
        const select = byId("report-model-filter");
        const models = report.status === "SUCCEEDED" ? (report.summary.source?.models || report.summary.models) : [];
        select.hidden = models.length < 2;
        select.replaceChildren(new Option("全部模型", ""), ...models.map(model => new Option(model.channelName + " · " + model.modelName, model.channelId)));
        select.value = modelFilter;
    }

    /** 详情顶部的六个页签，点击切换当前页签并重新渲染内容。 */
    function renderTabs() {
        const container = byId("report-tabs");
        container.replaceChildren();
        for (const {key, title} of view.tabs) {
            const tab = element("button", "report-tab", title); tab.type = "button"; tab.setAttribute("role", "tab");
            tab.setAttribute("aria-selected", String(key === activeTab));
            tab.addEventListener("click", () => { activeTab = key; renderTabs(); renderPanel(); });
            container.append(tab);
        }
    }

    /** 详情内容：报告未完成时显示判定进度；完成后渲染当前页签，有模型筛选时先给出筛选说明。 */
    function renderPanel() {
        const panel = byId("report-panel");
        panel.replaceChildren();
        if (report.status !== "SUCCEEDED") {
            const progress = element("div", "report-progress");
            if (report.status === "FAILED") progress.append(element("strong", null, "报告生成失败：" + (report.errorMessage || "原因未知")));
            else progress.append(element("strong", null, view.statusLabels[report.status] + "：已判定 " + progressText(report.progress)));
            progress.append(element("p", "help", "报告完成后展示指标；判定使用已启用渠道中分数最高的模型，失败的回答不计入依赖判定的指标。"));
            panel.append(progress);
            return;
        }
        if (modelFilter) panel.append(view.buildModelFilterNote(selectedModelName()));
        view.renderTab({modelFilter, entries:pageEntries}, panel, activeTab, report.summary);
    }

    /** @return {string|undefined} 模型筛选下拉框当前选中的模型名称，用于筛选说明 */
    function selectedModelName() {
        return byId("report-model-filter").selectedOptions[0]?.textContent;
    }

    /** 「导出网页」按钮：导出期间禁用并显示进度，失败时在详情顶部提示原因；网页由 DiagnosisReportExport 组装和下载。 */
    async function exportReportHtml() {
        const button = byId("export-report-html");
        button.disabled = true; button.textContent = "正在准备…"; show("detail-error", "");
        try {
            await window.DiagnosisReportExport.downloadReport(report, {modelFilter, modelName:selectedModelName(),
                onProgress:(done, total) => { button.textContent = "正在读取回答 " + done + "/" + total; }});
        } catch (error) {
            show("detail-error", "导出失败：" + error.message);
        } finally {
            button.disabled = false; button.textContent = "导出网页";
        }
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

    /**
     * 页面上的查看入口按钮。
     * @param {string} text 按钮文字
     * @param {function():void} onClick 点击时打开对应弹窗
     * @return {HTMLElement} 按钮
     */
    function entryButton(text, onClick) {
        const button = element("button", "text-button", text); button.type = "button";
        button.addEventListener("click", onClick);
        return button;
    }

    /**
     * 证据弹窗：展示所选问题的数字和证据（回答原文或问题），可继续打开完整回答。
     * @param {string[]} issueIds 问题编号
     */
    function openEvidence(issueIds) {
        const content = byId("evidence-content");
        content.replaceChildren();
        view.renderEvidence({modelFilter, entries:pageEntries}, content, report.summary, issueIds);
        if (!evidenceDialog.open) evidenceDialog.showModal();
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

    // 列表读取失败由轮询的 onError 在列表上方提示，loadList 本身不会失败，这里不再处理
    byId("report-filters").addEventListener("submit", event => { event.preventDefault(); page = 0; loadList(); });
    byId("report-previous").addEventListener("click", () => { page--; loadList(); });
    byId("report-next").addEventListener("click", () => { page++; loadList(); });
    byId("new-report").addEventListener("click", () => openCreate(null).catch(error => show("create-error", error.message)));
    byId("close-create").addEventListener("click", () => { if (!creating) createDialog.close(); });
    byId("cancel-create").addEventListener("click", () => { if (!creating) createDialog.close(); });
    byId("create-report-form").addEventListener("submit", submitCreate);
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
            // 关闭详情时一并结束轮询、回答读取和子弹窗，并清掉当前报告
            if (dialog === detailDialog) {
                detailPoller.stop();
                answerRequests.cancel();
                answerDialog.close();
                evidenceDialog.close();
                reportId = null;
                report = null;
            }
            if (dialog === answerDialog) { answerRequests.cancel(); answerId = null; }
        }
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
