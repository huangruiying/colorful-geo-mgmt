"use strict";
/**
 * 诊断报告六个页签的渲染：把报告汇总画成页面元素，系统页面与导出网页共用，保证两边内容一致。
 * <p>需要模型筛选或查看入口的函数，第一个参数都是 context：modelFilter 为当前筛选的模型 ID（空字符串为全部模型），
 * entries 决定「看证据」「看回答」入口的样子（页面上是按钮，导出网页里是打开弹窗的链接），见 renderTab。</p>
 * <p>只渲染、不发请求，也不读写页面上的其他元素。报告汇总由后端 domain/service/diagnosis/report 下的各生成类产出，
 * 各页签渲染函数注明了数据来源，字段含义以后端为准。</p>
 */
(() => {
    const statusLabels = Object.freeze({PENDING:"待生成", RUNNING:"生成中", SUCCEEDED:"已完成", FAILED:"失败"});
    const strengthLabels = {FIRST_CHOICE:"首推", STRONG:"强推", PARALLEL:"并列", PASSING:"顺带一提", WITH_RESERVATION:"附带缺点", NOT_RECOMMENDED:"不推荐"};
    const intentLabels = {RECOMMENDATION:"推荐选择", PROBLEM_SOLVING:"解决问题", COMPARISON:"对比", PURCHASE:"购买决策", TECHNICAL:"技术细节", AWARENESS:"了解认知"};
    const sourceTypeLabels = {OWNED:"官网", PUBLISHED:"我方发布", THIRD_PARTY_TARGET:"提到目标的第三方", COMPETITOR:"提到竞品", OTHER:"其他"};
    const categoryLabels = {FILL_CONTENT_GAP:"补内容空缺", IMPROVE_CONTENT:"完善已有内容", ADD_EVIDENCE:"增加可信证据",
        CORRECT_INFORMATION:"纠正和统一信息", IMPROVE_ACCESS:"改善公开访问与检索", ADD_THIRD_PARTY:"补充第三方覆盖"};
    const evidenceStatusLabels = {ANSWER_STATED:"回答明确表达", INFERRED:"待验证推测"};
    // 子 Tab 与诊断报告全景一致；part 为页签对应的汇总字段，缺失时显示补全提示
    const tabs = Object.freeze([
        {key:"overview", title:"① 诊断概览", render:renderOverview},
        {key:"visibility", title:"② 可见度与推荐", render:renderVisibility},
        {key:"brand", title:"③ 品牌认知", part:"brand", render:renderBrand},
        {key:"competitor", title:"④ 竞品对比", part:"competitor", render:renderCompetitor},
        {key:"source", title:"⑤ 引用与内容", part:"source", render:renderSource},
        {key:"advice", title:"⑥ 优化建议", part:"advice", render:renderAdvice}
    ].map(Object.freeze));
    const percent = value => value == null ? "—" : (value * 100).toFixed(1) + "%";
    const element = (tag, className, text) => {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    };

    /**
     * 渲染一个页签的内容；页面切换页签与导出网页逐个输出页签都走这里，保证两边内容一致。
     * @param {{modelFilter:string, entries:{evidence:function(string[]):HTMLElement, answer:function(number):HTMLElement}}} context
     *     modelFilter 为当前筛选的模型 ID，空字符串为全部模型；entries.evidence 按问题编号生成「看证据」入口，entries.answer 按诊断结果 ID 生成「看回答」入口
     * @param {HTMLElement} panel 内容写入的容器
     * @param {string} key 页签标识，取自 tabs
     * @param {object} summary 已完成报告的冻结汇总
     */
    function renderTab(context, panel, key, summary) {
        const tab = tabs.find(item => item.key === key);
        // 页签内容缺失时提示用右上角「补全报告」补全
        if (tab.part && !summary[tab.part]) panel.append(supplementNotice());
        else tab.render(context, panel, summary);
    }

    /**
     * @param {object} detail 报告详情
     * @return {string} 报告所属方案、项目、诊断任务、判定模型与状态的一行说明，详情抬头与导出网页抬头共用
     */
    function buildReportNote(detail) {
        const judge = detail.judgeChannel ? (detail.judgeChannel.channelName || "—") + " · " + (detail.judgeChannel.modelName || "—") : "未开始";
        return (detail.planName || "—") + " / " + (detail.projectName || "—") + " · 诊断任务 #" + detail.diagnosisTaskId
            + " · 判定模型：" + judge + " · " + (statusLabels[detail.status] || detail.status);
    }

    /**
     * 已选模型时的筛选说明，提醒哪些表格只含该模型、哪些仍是全部模型合计。
     * @param {string} modelName 所选模型的显示名称
     * @return {HTMLElement} 说明段落
     */
    function buildModelFilterNote(modelName) {
        return element("p", "report-filter-note", "已筛选模型：" + modelName + "。按模型分行的表格只显示该模型；维度排行、链接与清单为全部模型合计。");
    }

    /** @return {HTMLElement} 报告内容缺失时页签里的提示，补全入口在详情右上角 */
    function supplementNotice() {
        return element("div", "report-progress", "报告内容缺失时，点击补全报告可补全");
    }

    /**
     * ① 诊断概览：样本说明、各模型核心指标、主要发现与前三条优先行动。
     * 数据：sample、models 由 ReportOverviewSummarizer 生成；主要发现与行动来自 advice（ReportLlmAnalyzer）与 issues（ReportIssueSummarizer）。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderOverview(context, panel, summary) {
        const sample = summary.sample, judge = sample.judgeChannel || {};
        const grid = element("div", "task-overview");
        for (const [label, value] of [["有效回答", sample.validAnswers], ["判定成功", sample.judged], ["判定失败", sample.judgeFailed],
            ["带来源回答", sample.withSources], ["实际联网回答", sample.searched], ["模型数", sample.models], ["题数", sample.questions],
            ["最大采样次数", sample.maxRepeat], ["判定模型", (judge.channelName || "—") + " · " + (judge.modelName || "—") + " · 提示词 " + (sample.promptVersion || "—")]]) {
            const item = element("div");
            item.append(element("span", null, label), element("strong", null, String(value ?? "—")));
            grid.append(item);
        }
        panel.append(section("样本说明", "只分析成功且未被截断的回答；依赖判定的指标只统计判定成功的回答。", grid));
        panel.append(section("各模型核心指标", "提及率、推荐率、首推率按非品牌问题计算；品牌正面率按点名问题计算；推荐份额 = 目标被推荐次数 ÷（目标 + 预设竞品被推荐次数）。",
            table(["模型", "判定回答", "提及率", "推荐率", "首推率", "品牌正面率", "推荐份额"], ofModel(context, summary.models).map(model => [
                model.channelName + " · " + model.modelName, model.judgedAnswers, percent(model.mentionRate), percent(model.recommendRate),
                percent(model.firstChoiceRate), percent(model.brandPositiveRate), percent(model.recommendShare)]))));
        panel.append(section("主要发现与优先行动", "由模型根据规则发现的问题归纳，每条都可以点「看证据」核对；完整内容见 ⑥ 优化建议。", renderKeyFindings(context, summary)));
    }

    /**
     * ① 概览中的主要发现与前三条建议，内容来自 summary.advice；建议或问题缺失时显示补全提示。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {object} summary 报告汇总
     * @return {HTMLElement} 主要发现与优先行动
     */
    function renderKeyFindings(context, summary) {
        const advice = summary.advice;
        if (!summary.issues || !summary.advice) return supplementNotice();
        if (advice.status === "NO_ISSUES") return element("p", "report-empty", "规则没有发现需要处理的问题。");
        if (advice.status === "FAILED") return element("p", "report-empty", advice.errorMessage + "，见 ⑥ 优化建议。");
        const box = element("div", "report-dimension-list");
        box.append(element("h4", null, "主要发现"));
        for (const finding of advice.findings) box.append(adviceItem(context, finding.content, null, null, finding.issueIds));
        box.append(element("h4", null, "优先行动"));
        for (const action of advice.actions.slice(0, 3)) box.append(adviceItem(context, action.title, categoryLabels[action.category], action.content, action.issueIds));
        return box;
    }

    /**
     * ② 可见度与推荐：非品牌问题中的可见度、推荐强度，以及推荐理由与用户关注点。
     * 数据：models 由 ReportOverviewSummarizer 生成；strength、reservations、criteria、concerns 由 ReportVisibilitySummarizer 生成。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderVisibility(context, panel, summary) {
        panel.append(section("可见度（非品牌问题）", "问题覆盖率：至少有一次提到目标的题数占比；稳定性：同一题多次采样中提及结果一致的题数占比。",
            table(["模型", "非品牌回答", "提及率", "推荐率", "首推率", "平均排名", "问题覆盖率", "稳定性"], ofModel(context, summary.models).map(model => [
                model.channelName + " · " + model.modelName, model.nonBrandAnswers, percent(model.mentionRate), percent(model.recommendRate),
                percent(model.firstChoiceRate), model.averageRank ?? "—", percent(model.questionCoverage), percent(model.stability)]))));
        const strengthKeys = Object.keys(strengthLabels);
        panel.append(section("推荐强度（非品牌问题中提到目标时）", "提到不等于推荐得好：顺带一提、附带缺点都说明推荐不够坚定。点名与对比问题必然提到目标，不计入。",
            table(["模型", ...strengthKeys.map(key => strengthLabels[key])], ofModel(context, summary.strength).map(row => [
                row.channelName, ...strengthKeys.map(key => row.distribution[key] || 0)]))));
        const lists = element("div", "report-dimensions");
        lists.append(dimensionList(context, "目标的保留意见", "被推荐时附带的缺点，是要补的短板", summary.reservations),
            dimensionList(context, "用户选择标准", "未提到目标的回答强调了什么", summary.criteria),
            dimensionList(context, "用户顾虑", "未提到目标的回答提醒了什么", summary.concerns));
        panel.append(section("推荐理由与用户关注点", "按归一后的维度统计次数，附回答原文。", lists));
    }

    /**
     * ③ 品牌认知：只看点名问题，模型怎么评价目标、描述对不对、负面来自哪里、卖点说没说到。
     * 数据：summary.brand，由 ReportBrandSummarizer 生成。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderBrand(context, panel, summary) {
        const brand = summary.brand;
        panel.append(section("各模型评价（点名问题）", "情感按回答计数；描述准确率只统计判定给出了对错的回答，依据是项目资料；卖点覆盖率 = 至少说到一个核心卖点的回答占比。",
            table(["模型", "点名回答", "正面", "中性", "负面", "描述准确率", "卖点覆盖率"], ofModel(context, brand.models).map(model => [
                model.channelName + " · " + model.modelName, model.brandAnswers, model.positive, model.neutral, model.negative,
                percent(model.accurateRate), percent(model.sellingPointRate)]))));
        const lists = element("div", "report-dimensions");
        lists.append(dimensionList(context, "正面点", "继续强化的优势", brand.positives),
            dimensionList(context, "负面点", "需要回应或纠正", brand.negatives),
            dimensionList(context, "提到的卖点", "模型已经知道的核心卖点", brand.sellingPoints));
        panel.append(section("正面点、负面点与卖点", "按归一后的维度统计次数，附回答原文。", lists));
        panel.append(section("负面来源", "判定为负面的点名回答引用了哪些平台，决定去哪里处理。", table(["平台", "引用次数", "链接示例"],
            brand.negativeSources.map(row => [row.platform, row.count, row.links.map(link => link.title || link.url).join("；")]), 1)));
        panel.append(section("错误信息清单", "与项目资料不符的描述，资料没写的内容不判对错。", quoteList(context, ofModel(context, brand.factErrors).map(error => ({
            title:error.content, quote:error.quote, channelName:error.channelName, resultId:error.resultId})))));
    }

    /**
     * ④ 竞品对比：目标与预设竞品同口径比较，再看同题先后、竞品强在哪些场景、对比维度谁赢。
     * 数据：summary.competitor，由 ReportCompetitorSummarizer 生成。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderCompetitor(context, panel, summary) {
        const competitor = summary.competitor;
        panel.append(section("目标与竞品（非品牌问题）", "与 ② 的口径相同，分母为该模型的非品牌回答；对比问题点了竞品的名，不计入提及率。",
            table(["模型", "品牌", "提及率", "推荐率", "首推率"], ofModel(context, competitor.brands).map(row => [
                row.channelName + " · " + row.modelName, row.brandName + (row.target ? "（目标）" : ""),
                percent(row.mentionRate), percent(row.recommendRate), percent(row.firstChoiceRate)]), 2)));
        panel.append(section("同题胜率", "同一条回答里目标和竞品都被推荐且都有排名时，目标排在前面的占比。",
            table(["模型", "竞品", "可比较的回答", "目标在前", "竞品在前", "同题胜率"], ofModel(context, competitor.headToHead).map(row => [
                row.channelName + " · " + row.modelName, row.competitor, row.sameAnswers, row.targetAhead, row.competitorAhead, percent(row.winRate)]), 2)));
        panel.append(section("竞品的优势场景", "按问题意图拆分非品牌问题，只列出竞品推荐率高于目标的组合。",
            table(["模型", "问题意图", "竞品", "回答数", "目标推荐率", "竞品推荐率"], ofModel(context, competitor.advantageScenes).map(row => [
                row.channelName, intentLabels[row.intent] || row.intent, row.competitor, row.answers,
                percent(row.targetRecommendRate), percent(row.competitorRecommendRate)]), 3)));
        const lists = element("div", "report-dimensions");
        lists.append(dimensionList(context, "分维度胜负（对比问题）", "目标相对竞品在各维度上的结果", competitor.dimensions,
                row => "赢 " + row.win + " · 输 " + row.lose + " · 平 " + row.tie),
            dimensionList(context, "竞品被推荐的理由", "只取回答里明确写出的理由", competitor.competitorReasons));
        panel.append(section("对比维度与竞品理由", "按归一后的维度统计，附回答原文。", lists));
        panel.append(section("新出现的同类产品", "不在预设竞品里、回答中出现过的产品，可考虑加入竞品。",
            table(["产品", "出现回答数", "被推荐回答数"], competitor.candidates.map(row => [row.name, row.mentions, row.recommended]))));
    }

    /**
     * ⑤ 引用与内容：模型引用了哪些来源，我方内容有没有被用上，竞品靠哪些内容，用户关心的方向有没有我方内容。
     * 数据：summary.source，由 ReportSourceSummarizer 生成。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderSource(context, panel, summary) {
        const source = summary.source;
        panel.append(section("各模型来源情况", "来源覆盖率 = 带来源回答 ÷ 全部回答；各类引用率的分母为带来源回答。各平台“引用”含义不同，只在同类平台之间比较。",
            table(["模型", "回答", "带来源", "来源覆盖率", "引用官网", "引用我方发布", "引用提到目标的第三方", "引用提到竞品的内容"], ofModel(context, source.models).map(model => [
                model.channelName + " · " + model.modelName, model.answers, model.withSources, percent(model.sourceCoverage),
                percent(model.ownedRate), percent(model.publishedRate), percent(model.thirdPartyRate), percent(model.competitorRate)]))));
        panel.append(section("各模型常引用的平台", "按被引用次数排序，每个模型最多 10 个。", table(["模型", "平台", "引用次数"],
            ofModel(context, source.platforms).flatMap(row => row.platforms.map(platform => [row.channelName, platform.platform, platform.count])), 2)));
        panel.append(section("被引用的我方链接", "官网域名下的页面，以及与发布记录一致的链接；按生成报告时的发布记录判断。",
            table(["链接", "标题", "类型", "引用回答数"], source.ownLinks.map(link => [link.url, link.title || "—", sourceTypeLabels[link.sourceType] || link.sourceType, link.count]), 3)));
        panel.append(section("竞品的来源", "推荐了预设竞品的回答引用了哪些内容，看竞品被推荐靠的是什么。",
            table(["链接", "标题", "平台", "类型", "引用回答数"], source.competitorLinks.map(link => [link.url, link.title || "—", link.platform || "—",
                sourceTypeLabels[link.sourceType] || link.sourceType, link.count]), 4)));
        const gaps = element("div", "report-dimensions");
        gaps.append(dimensionList(context, "用户关心的方向", "引用我方为 0 的方向，说明我方内容没被用上（可能没有、不公开或不好找），需要人工确认",
            source.contentGaps, row => "提到 " + row.answers + " 条 · 引用我方 " + row.ownCitedAnswers + " 条"));
        panel.append(section("内容差距", "非品牌问题中回答强调的选择标准和用户顾虑，相关回答有没有引用官网或我方发布内容。", gaps));
    }

    /**
     * ⑥ 优化建议：模型写的建议与主要发现，以及规则发现的全部问题；每条都能打开证据。
     * 数据：advice 由 ReportLlmAnalyzer 调用模型生成，issues 由 ReportIssueSummarizer 按固定阈值生成。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} panel 内容写入的容器
     * @param {object} summary 报告汇总
     */
    function renderAdvice(context, panel, summary) {
        const advice = summary.advice;
        if (advice.status === "SUCCEEDED") {
            const actions = element("div", "report-dimension-list");
            if (!advice.actions.length) actions.append(element("p", "report-empty", "暂无"));
            advice.actions.forEach((action, index) => actions.append(adviceItem(context, (index + 1) + ". " + action.title,
                categoryLabels[action.category] + " · " + evidenceStatusLabels[action.evidenceStatus], action.content, action.issueIds)));
            panel.append(section("优化建议（按优先级）", "由模型根据下方规则发现的问题归纳；“待验证推测”表示原因或做法是推断，需要先核实。", actions));
            const findings = element("div", "report-dimension-list");
            if (!advice.findings.length) findings.append(element("p", "report-empty", "暂无"));
            for (const finding of advice.findings) findings.append(adviceItem(context, finding.content, null, null, finding.issueIds));
            panel.append(section("主要发现", null, findings));
        } else {
            panel.append(element("div", "report-progress", advice.status === "NO_ISSUES" ? "规则没有发现需要处理的问题，未生成建议。" : advice.errorMessage));
        }
        const issues = element("div", "report-dimension-list");
        // 筛选模型时只列该模型的问题；不属于单个模型的问题（channelId 为空）始终列出
        const visibleIssues = summary.issues.filter(issue => !context.modelFilter || issue.channelId == null || String(issue.channelId) === context.modelFilter);
        if (!visibleIssues.length) issues.append(element("p", "report-empty", "暂无"));
        for (const issue of visibleIssues) issues.append(adviceItem(context, issue.id + " " + issue.title, metricsText(issue.metrics), null, [issue.id]));
        panel.append(section("规则发现的问题", "由程序按固定阈值从 ②–⑤ 的结果中挑出，不经过模型；建议只能引用这里的编号。", issues));
        panel.append(section("复测方式", null, element("p", "help",
            "按建议调整后，用同一诊断方案（原问题、原渠道配置）重新执行诊断任务并生成报告；判定模型与提示词版本一致时，再与本报告对比。")));
    }

    /**
     * 建议、发现或问题的一行：标题、补充说明、正文，以及「看证据」入口。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {string} title 标题
     * @param {string|null} meta 标题后的补充说明，没有时为 null
     * @param {string|null} content 正文，没有时为 null
     * @param {string[]} issueIds 支撑这条内容的问题编号
     * @return {HTMLElement} 一行内容
     */
    function adviceItem(context, title, meta, content, issueIds) {
        const item = element("div", "report-dimension report-advice-item");
        item.append(element("strong", null, title));
        if (meta) item.append(element("span", "report-advice-meta", meta));
        if (content) item.append(element("p", "report-advice-content", content));
        const line = element("p", "report-quote");
        line.append(context.entries.evidence(issueIds));
        item.append(line);
        return item;
    }

    /**
     * @param {{label:string, value:number, rate:boolean}[]} metrics 问题的支撑数字
     * @return {string} 支撑数字的一行文字，比率按百分比展示
     */
    function metricsText(metrics) {
        return metrics.map(metric => metric.label + " " + (metric.rate ? percent(metric.value) : metric.value)).join(" · ");
    }

    /**
     * 逐个问题写出编号标题、支撑数字和证据；系统中的证据弹窗与导出网页中的证据弹窗共用。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {HTMLElement} container 写入的容器；加上证据样式类，两处证据弹窗的标题样式一致
     * @param {object} summary 证据所属报告的冻结汇总
     * @param {string[]} issueIds 问题编号，按报告中的问题顺序输出
     */
    function renderEvidence(context, container, summary, issueIds) {
        container.classList.add("report-evidence");
        for (const issue of summary.issues.filter(item => issueIds.includes(item.id))) {
            container.append(element("h4", null, issue.id + " " + issue.title), element("p", "help", metricsText(issue.metrics)));
            if (!issue.evidence.length) container.append(element("p", "report-empty", "此问题由上面的数字支撑，没有对应的回答原文。"));
            for (const item of issue.evidence) {
                const from = item.channelName + (item.brand ? " · " + item.brand : "");
                container.append(item.quote != null ? quoteLine(context, item.quote, from, item.resultId)
                    : answerLine(context, "问题：" + item.question + " — " + from, item.resultId));
            }
        }
    }

    /**
     * 维度排行：每个维度一行，附回答原文示例；示例中有品牌时一并展示。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {string} title 标题
     * @param {string} help 标题下的说明
     * @param {object[]} rows 维度排行，每行含 dimension、examples 及计数
     * @param {function(object):string} describe 每行右侧的计数写法，默认“N 次”
     * @return {HTMLElement} 维度排行卡片
     */
    function dimensionList(context, title, help, rows, describe = row => row.count + " 次") {
        const box = element("div", "report-dimension-list");
        box.append(element("h4", null, title), element("p", "help", help));
        if (!rows.length) box.append(element("p", "report-empty", "暂无"));
        for (const row of rows) {
            const item = element("div", "report-dimension");
            item.append(element("strong", null, row.dimension), element("span", "count", describe(row)));
            for (const example of row.examples) item.append(quoteLine(context, example.quote, example.channelName + (example.brand ? " · " + example.brand : ""), example.resultId));
            box.append(item);
        }
        return box;
    }

    /**
     * 逐条清单（如错误信息）：每条一个标题加一句原文。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {{title:string, quote:string, channelName:string, resultId:number}[]} rows 清单
     * @return {HTMLElement} 清单卡片
     */
    function quoteList(context, rows) {
        const box = element("div", "report-dimension-list");
        if (!rows.length) box.append(element("p", "report-empty", "暂无"));
        for (const row of rows) {
            const item = element("div", "report-dimension");
            item.append(element("strong", null, row.title || "—"), quoteLine(context, row.quote, row.channelName, row.resultId));
            box.append(item);
        }
        return box;
    }

    /**
     * 一句回答原文加来源，以及「看回答」入口。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {string} quote 回答原文
     * @param {string} from 来源（模型，有品牌时带品牌）
     * @param {number} resultId 诊断结果 ID
     * @return {HTMLElement} 一行内容
     */
    function quoteLine(context, quote, from, resultId) {
        return answerLine(context, "“" + quote + "” — " + from, resultId);
    }

    /**
     * 一行说明文字加「看回答」入口。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {string} text 说明文字
     * @param {number} resultId 诊断结果 ID
     * @return {HTMLElement} 一行内容
     */
    function answerLine(context, text, resultId) {
        const line = element("p", "report-quote", text);
        line.append(context.entries.answer(resultId));
        return line;
    }

    /**
     * 带标题和说明的一段内容。
     * @param {string} title 标题
     * @param {string|null} help 标题下的说明，没有时为 null
     * @param {...HTMLElement} children 正文
     * @return {HTMLElement} 段落
     */
    function section(title, help, ...children) {
        const node = element("section", "report-section");
        node.append(element("h3", null, title));
        if (help) node.append(element("p", "help", help));
        node.append(...children);
        return node;
    }

    /**
     * 数据表格；单元格统一用文本节点写入，模型原文不作为 HTML 解析。
     * @param {string[]} headers 表头
     * @param {Array<Array<string|number>>} rows 每行的单元格
     * @param {number} metricFrom 从第几列（0 起）开始是数字，数字列右对齐，默认第 1 列
     * @return {HTMLElement} 可横向滚动的表格
     */
    function table(headers, rows, metricFrom = 1) {
        const wrapper = element("div", "table-scroll"), tableNode = element("table", "report-table");
        const head = tableNode.createTHead().insertRow();
        headers.forEach((text, index) => {
            const cell = element("th", index >= metricFrom ? "metric" : null, text);
            cell.scope = "col";
            head.append(cell);
        });
        const body = tableNode.createTBody();
        if (!rows.length) {
            const cell = body.insertRow().insertCell();
            cell.colSpan = headers.length;
            cell.className = "table-message";
            cell.textContent = "暂无数据";
        }
        for (const values of rows) {
            const row = body.insertRow();
            values.forEach((value, index) => {
                const cell = row.insertCell();
                if (index >= metricFrom) cell.className = "metric";
                cell.textContent = value;
            });
        }
        wrapper.append(tableNode);
        return wrapper;
    }

    /**
     * 按模型筛选：只作用于按模型分行的表格，排行与清单是全部模型合计。
     * @param {object} context 模型筛选与查看入口，见 renderTab
     * @param {object[]} rows 按模型分行的数据，每行带 channelId
     * @return {object[]} 筛选后的行；未筛选时原样返回
     */
    function ofModel(context, rows) {
        return context.modelFilter ? rows.filter(row => String(row.channelId) === context.modelFilter) : rows;
    }

    window.DiagnosisReportView = Object.freeze({statusLabels, tabs, buildReportNote, buildModelFilterNote, renderTab, renderEvidence});
})();
