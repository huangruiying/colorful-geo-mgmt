"use strict";
/**
 * 诊断报告导出网页：把已完成报告连同证据与回答原文组装成可离线打开的独立网页并下载，六个页签与系统中一样切换查看。
 * <p>内容与页面当前看到的一致，沿用模型筛选；页签内容由 DiagnosisReportView 渲染。页签切换与「看证据」「看回答」弹窗
 * 由写入网页的 exportPageScript 完成，弹窗内容随网页一起导出，只导出正文引用到的证据与回答。</p>
 * <p>只负责组装与下载；导出按钮的状态和失败提示由报告页负责。</p>
 */
(() => {
    const view = window.DiagnosisReportView;
    const element = (tag, className, text) => {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    };

    /**
     * 组装并下载报告的导出网页；回答原文逐条读取，任一条失败就不下载，避免文件缺内容。
     * @param {object} report 已完成的报告
     * @param {{modelFilter:string, modelName:string, onProgress:function(number, number):void}} options
     *     modelFilter 为当前筛选的模型 ID（空字符串为全部模型）；modelName 为筛选说明中显示的模型名称；
     *     onProgress 每读完一条回答回调一次，参数为已读条数和总条数
     * @return {Promise<void>} 交给浏览器下载后完成；读取回答失败时以该错误拒绝
     */
    async function downloadReport(report, {modelFilter, modelName, onProgress}) {
        // 1. 抬头：报告编号、所属任务与判定模型、导出时间，有模型筛选时一并注明
        const documentNode = element("article", "report-export");
        documentNode.append(element("h1", null, "诊断报告 #" + report.id), element("p", "report-export-note", view.buildReportNote(report)),
            element("p", "report-export-note", "导出时间：" + window.AdminFormat.dateTime(new Date())));
        if (modelFilter) documentNode.append(view.buildModelFilterNote(modelName));
        // 导出网页无法补全，缺失内容在抬头提醒先补全再导出
        if (report.missingParts?.length) documentNode.append(element("p", "report-filter-note", "本报告有缺失内容（见各部分提示），建议先在系统中点击「补全报告」，完成后再导出。"));

        // 2. 页签栏与六个页签内容；查看入口是打开弹窗的链接，同时收集正文引用到的证据和回答
        const refs = {evidenceGroups:new Map(), resultIds:new Set()};
        const context = {modelFilter, entries:buildExportEntries(refs)};
        appendExportTabs(context, documentNode, report.summary);

        // 3. 证据弹窗：每组「看证据」一个，内容与系统中的证据弹窗相同；证据里的回答也计入要导出的回答
        for (const [groupKey, issueIds] of refs.evidenceGroups) {
            const content = element("div");
            view.renderEvidence(context, content, report.summary, issueIds);
            documentNode.append(exportDialog("evidence-" + groupKey, "证据", "由规则从本报告数据中计算，点「看回答」可查看完整回答。", content));
        }

        // 4. 回答弹窗：逐条读取回答原文，每条一个
        const resultIds = [...refs.resultIds];
        const answers = await loadAnswers(report.diagnosisTaskId, resultIds, done => onProgress(done, resultIds.length));
        for (const answer of answers) {
            const body = element("div", "answer-body");
            MarkdownView.render(body, answer.answerText || "无回答正文");
            documentNode.append(exportDialog("answer-" + answer.id, "回答原文",
                (answer.channelName || "—") + " · 问题：" + (answer.questionText || "—"), body));
        }

        // 5. 交给公共导出模块，附上切换页签、打开弹窗的脚本；文件名带方案名与报告编号，便于区分多份报告
        window.AdminHtmlExport.downloadPage(documentNode, {
            title:"诊断报告-" + (report.planName || "未命名方案") + "-" + report.id, bodyClass:"diagnosis-page",
            script:"(" + exportPageScript + ")();"});
    }

    /**
     * 导出网页中的查看入口：「看证据」「看回答」是打开随网页导出的弹窗的链接；同时记下引用了哪些证据和回答，供生成弹窗。
     * @param {{evidenceGroups:Map<string, string[]>, resultIds:Set<number>}} refs 收集引用的容器，按出现先后记录
     * @return {{evidence:function(string[]):HTMLElement, answer:function(number):HTMLElement}} 查看入口，见 DiagnosisReportView.renderTab
     */
    function buildExportEntries(refs) {
        return {
            evidence:issueIds => {
                // 同一组问题共用一个证据弹窗
                const groupKey = issueIds.join("-");
                refs.evidenceGroups.set(groupKey, issueIds);
                return dialogLink("evidence-" + groupKey, "看证据（" + issueIds.join("、") + "）");
            },
            answer:resultId => {
                refs.resultIds.add(resultId);
                return dialogLink("answer-" + resultId, "看回答");
            }
        };
    }

    /**
     * 导出网页中打开弹窗的链接，样式与页面上的文字按钮一致。
     * @param {string} dialogId 要打开的弹窗标识
     * @param {string} text 链接文字
     * @return {HTMLElement} 链接
     */
    function dialogLink(dialogId, text) {
        const link = element("a", "text-button", text);
        link.href = "#"; link.dataset.openDialog = dialogId;
        return link;
    }

    /**
     * 导出网页的页签栏与六个页签内容，结构和样式与页面上的页签相同；默认显示第一个，其余隐藏，由导出网页内置的脚本切换。
     * @param {object} context 模型筛选与导出网页的查看入口
     * @param {HTMLElement} documentNode 导出内容，页签栏和内容依次追加到末尾
     * @param {object} summary 已完成报告的冻结汇总
     */
    function appendExportTabs(context, documentNode, summary) {
        const tabBar = element("div", "report-tabs");
        tabBar.setAttribute("role", "tablist");
        documentNode.append(tabBar);
        view.tabs.forEach(({key, title}, index) => {
            const tab = element("a", "report-tab", title);
            tab.href = "#"; tab.dataset.exportTab = key;
            tab.setAttribute("role", "tab"); tab.setAttribute("aria-selected", String(index === 0));
            tabBar.append(tab);
            const panel = element("section");
            panel.dataset.exportPanel = key; panel.hidden = index > 0;
            view.renderTab(context, panel, key, summary);
            documentNode.append(panel);
        });
    }

    /**
     * 逐条读取导出要用的回答原文，按传入顺序返回。
     * @param {number} taskId 诊断任务 ID
     * @param {number[]} resultIds 诊断结果 ID，已去重
     * @param {function(number):void} onProgress 每读完一条回调一次，参数为已读完的条数
     * @return {Promise<object[]>} 回答详情；任一条失败即整体失败
     */
    async function loadAnswers(taskId, resultIds, onProgress) {
        const answers = [];
        for (const resultId of resultIds) {
            answers.push(await window.AdminHttp.requestJson("/diagnosis-tasks/" + taskId + "/results/" + resultId));
            onProgress(answers.length);
        }
        return answers;
    }

    /**
     * 导出网页中的一个弹窗，结构和样式与系统中的证据、回答弹窗相同；平时隐藏，由「看证据」「看回答」打开。
     * @param {string} id 弹窗标识，打开链接按它找到弹窗
     * @param {string} title 标题
     * @param {string} note 标题下的说明
     * @param {HTMLElement} content 弹窗正文
     * @return {HTMLElement} 弹窗
     */
    function exportDialog(id, title, note, content) {
        const dialog = element("dialog", "task-dialog task-answer-dialog");
        dialog.id = id;
        const heading = element("div", "panel-heading"), text = element("div");
        text.append(element("h2", null, title), element("p", "help", note));
        // 导出时页面按钮会被去掉，关闭入口用链接
        const close = element("a", "refresh-button", "返回报告");
        close.href = "#"; close.dataset.closeDialog = "";
        heading.append(text, close);
        const body = element("div", "task-dialog-body");
        body.append(content);
        dialog.append(heading, body);
        return dialog;
    }

    /**
     * 导出网页内置的脚本：点页签切换显示的内容；点「看证据」「看回答」打开对应弹窗，点「返回报告」或弹窗外的遮罩关闭，弹窗可以叠开。
     * 只在导出的网页里运行，不在系统页面中调用；以源码形式写入网页，因此不引用本文件的其他变量。
     */
    function exportPageScript() {
        document.addEventListener("click", event => {
            const tab = event.target.closest("[data-export-tab]");
            const opener = event.target.closest("[data-open-dialog]");
            const closer = event.target.closest("[data-close-dialog]");
            if (tab) {
                event.preventDefault();
                document.querySelectorAll("[data-export-tab]").forEach(item => item.setAttribute("aria-selected", String(item === tab)));
                document.querySelectorAll("[data-export-panel]").forEach(panel => { panel.hidden = panel.dataset.exportPanel !== tab.dataset.exportTab; });
            } else if (opener) {
                event.preventDefault();
                document.getElementById(opener.dataset.openDialog).showModal();
            } else if (closer) {
                event.preventDefault();
                closer.closest("dialog").close();
            } else if (event.target instanceof HTMLDialogElement) {
                // 点在弹窗外的遮罩上时，事件目标是弹窗本身
                event.target.close();
            }
        });
    }

    window.DiagnosisReportExport = Object.freeze({downloadReport});
})();
