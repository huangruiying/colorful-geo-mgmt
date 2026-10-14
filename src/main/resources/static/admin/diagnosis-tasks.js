"use strict";
(() => {
    const byId = id => document.getElementById(id);
    const createDialog = byId("create-task-dialog"), detailDialog = byId("task-detail-dialog");
    const answerDialog = byId("answer-dialog"), informationDialog = byId("information-dialog");
    let frozenSnapshot = null;
    let restarting = false;
    const labels = {PENDING:"待执行", RUNNING:"执行中", SUCCEEDED:"成功", PARTIAL_FAILED:"部分失败", FAILED:"失败"};
    let page = 0, resultPage = 0, taskId = null, preview = null, channels = [], creating = false;
    let listRevision = 0, detailRevision = 0, previewRevision = 0, listTimer = null, detailTimer = null;
    let answerId = null, answerRevision = 0, answerStatus = null;
    let memorySubmission = null;
    const pendingKey = "diagnosis-task-pending-create";
    const active = status => status === "PENDING" || status === "RUNNING";
    const time = value => value?.replace("T", " ") || "—";
    const show = (id, text) => { byId(id).textContent = text; byId(id).hidden = !text; };
    const button = (text, action) => {
        const element = document.createElement("button"); element.type = "button";
        element.className = "text-button"; element.textContent = text;
        element.addEventListener("click", action); return element;
    };
    const fail = error => show(answerDialog.open ? "answer-error" : detailDialog.open ? "detail-error" : "task-error", error.message);
    async function request(path, options = {}) {
        const response = await fetch(path, {cache:"no-store", headers:{"Content-Type":"application/json"}, ...options});
        let body;
        try { body = await response.json(); } catch { throw new Error("服务响应异常，请刷新后重试"); }
        if (!response.ok) throw new Error(body.detail || "操作失败，请检查输入或服务状态");
        return body;
    }
    async function allItems(path) {
        const items = [];
        for (let current = 0; ; current++) {
            const result = await request(path + "?page=" + current + "&size=100");
            items.push(...result.items);
            if (!result.items.length || items.length >= result.total) return items;
        }
    }
    function statusBadge(status) {
        const span = document.createElement("span"); span.className = "task-status";
        span.dataset.status = status; span.textContent = labels[status] || status; return span;
    }
    function overview(target, fields) {
        target.replaceChildren();
        for (const [label, value, wide] of fields) {
            const item = document.createElement("div"); if (wide) item.className = "wide";
            const caption = document.createElement("span"), text = document.createElement("strong");
            caption.textContent = label; text.textContent = value;
            item.append(caption, text); target.append(item);
        }
    }
    function emptyRow(rows, columns, text) {
        const cell = rows.insertRow().insertCell(); cell.colSpan = columns;
        cell.className = "table-message"; cell.textContent = text;
    }
    async function loadOptions() {
        const [plans, models] = await Promise.all([allItems("/diagnosis-plans"), allItems("/llm-channels")]);
        channels = models;
        const selected = byId("filter-plan").value;
        byId("filter-plan").replaceChildren(new Option("全部方案", ""));
        byId("create-plan").replaceChildren(new Option("请选择方案", ""));
        for (const plan of plans) {
            const label = plan.name + " · " + (plan.projectName || "项目不可用");
            byId("filter-plan").add(new Option(label, plan.id));
            byId("create-plan").add(new Option(label, plan.id));
        }
        byId("filter-plan").value = selected;
        byId("new-task").disabled = !plans.length;
        if (!plans.length) show("task-success", "请先在「诊断方案」创建并保存问题集合。");
    }
    async function loadList() {
        clearTimeout(listTimer);
        const revision = ++listRevision;
        const query = new URLSearchParams({page, size:10});
        if (byId("filter-plan").value) query.set("planId", byId("filter-plan").value);
        if (byId("filter-status").value) query.set("status", byId("filter-status").value);
        const result = await request("/diagnosis-tasks?" + query);
        if (revision !== listRevision) return;
        const rows = byId("task-list"); rows.replaceChildren();
        if (!result.items.length) emptyRow(rows, 7, "暂无诊断任务，点击「发起诊断」执行已保存的方案。");
        for (const task of result.items) {
            const row = rows.insertRow(); row.insertCell().textContent = "#" + task.id;
            const planCell = row.insertCell(); planCell.textContent = task.planName;
            const project = document.createElement("small"); project.className = "task-secondary";
            project.textContent = task.projectName; planCell.append(project);
            row.insertCell().append(statusBadge(task.status));
            row.insertCell().textContent = (task.progress.succeeded + task.progress.failed) + " / " + task.progress.total;
            row.insertCell().textContent = task.progress.succeeded + " / " + task.progress.failed;
            row.insertCell().textContent = time(task.createdAt);
            row.insertCell().append(button("查看", () => openDetail(task.id).catch(fail)));
        }
        byId("task-page-info").textContent = "第 " + (page + 1) + " 页 · 共 " + result.total + " 个任务";
        byId("task-previous").disabled = page === 0;
        byId("task-next").disabled = (page + 1) * 10 >= result.total;
        if (result.items.some(task => active(task.status))) {
            listTimer = setTimeout(() => loadList().catch(fail), 3000);
        }
    }
    async function openCreate() {
        await loadOptions();
        preview = null; previewRevision++; byId("create-submit").disabled = true;
        byId("create-task-form").reset(); show("create-error", "");
        overview(byId("create-preview"), [["本轮配置", "请选择一个已保存的方案", true]]);
        createDialog.showModal(); document.body.classList.add("task-dialog-open");
    }
    async function loadPreview() {
        const revision = ++previewRevision;
        const id = byId("create-plan").value; preview = null; byId("create-submit").disabled = true;
        show("create-error", "");
        if (!id) {
            overview(byId("create-preview"), [["本轮配置", "请选择方案", true]]); return;
        }
        const detail = await request("/diagnosis-plans/" + id);
        if (revision !== previewRevision || !createDialog.open) return;
        preview = detail;
        const count = detail.questions.length * detail.channelIds.length * detail.repeatCount;
        overview(byId("create-preview"), [["关联项目", detail.projectName], ["问题数量", detail.questions.length + " 个"],
            ["重复执行次数", detail.repeatCount + " 次"], ["被测渠道", detail.channelIds.map(channelId =>
                channels.find(channel => channel.id === channelId)?.channelName || "不可用渠道 #" + channelId).join("、"), true],
            ["预计调用次数", count + " 次", true]]);
        byId("create-submit").disabled = count === 0;
    }
    // 网络结果不确定时保留提交标识；刷新后再次提交同一方案也不会重复创建。
    function submissionId(planId) {
        let saved = memorySubmission;
        try {
            const stored = JSON.parse(sessionStorage.getItem(pendingKey));
            if (stored?.requestId) saved = stored;
        } catch { /* 存储不可读时继续使用本页的提交标识。 */ }
        if (saved?.planId === planId && saved.requestId) return saved.requestId;
        const requestId = typeof crypto.randomUUID === "function" ? crypto.randomUUID()
            : Array.from(crypto.getRandomValues(new Uint8Array(16)), value => value.toString(16).padStart(2, "0")).join("");
        memorySubmission = {planId, requestId};
        try { sessionStorage.setItem(pendingKey, JSON.stringify(memorySubmission)); } catch { /* 存储不可用时本页仍复用同一提交。 */ }
        return requestId;
    }
    async function createTask(event) {
        event.preventDefault();
        if (creating || !preview || preview.id !== Number(byId("create-plan").value)) return;
        creating = true; byId("create-submit").disabled = true; byId("create-plan").disabled = true;
        show("create-error", "");
        try {
            const task = await request("/diagnosis-tasks", {method:"POST", body:JSON.stringify({planId:preview.id, requestId:submissionId(preview.id)})});
            memorySubmission = null;
            try { sessionStorage.removeItem(pendingKey); } catch { /* 创建成功不受浏览器存储权限影响。 */ }
            createDialog.close(); page = 0;
            show("task-success", "任务 #" + task.id + " 已创建，共 " + task.progress.total + " 次诊断，后台将按顺序执行。");
            try { await loadList(); await openDetail(task.id); }
            catch (error) { show("task-error", "任务已创建，页面刷新失败：" + error.message); }
        } catch (error) { show("create-error", error.message); }
        finally { creating = false; byId("create-submit").disabled = !preview; byId("create-plan").disabled = false; }
    }
    async function openDetail(id) {
        clearTimeout(detailTimer); taskId = id; resultPage = 0; answerId = null; answerRevision++;
        detailRevision++; frozenSnapshot = null; byId("view-snapshot").disabled = true; show("detail-error", "");
        byId("result-status").value = ""; byId("result-channel").replaceChildren(new Option("全部渠道", ""));
        byId("task-detail-title").textContent = "查看诊断任务 #" + id;
        byId("restart-task").hidden = true;
        byId("task-overview").replaceChildren(); byId("channel-progress").replaceChildren();
        byId("result-list").replaceChildren();
        detailDialog.showModal(); document.body.classList.add("task-dialog-open");
        await refreshDetail();
    }
    async function refreshDetail() {
        clearTimeout(detailTimer);
        const id = taskId, revision = ++detailRevision;
        const task = await request("/diagnosis-tasks/" + id);
        if (!detailDialog.open || taskId !== id || revision !== detailRevision) return;
        byId("restart-task").hidden = !["FAILED", "PARTIAL_FAILED"].includes(task.status);
        byId("restart-task").disabled = restarting;
        overview(byId("task-overview"), [["诊断方案", task.planName], ["关联项目", task.projectName], ["任务状态", labels[task.status]],
            ["诊断进度", (task.progress.succeeded + task.progress.failed) + " / " + task.progress.total],
            ["成功 / 失败", task.progress.succeeded + " / " + task.progress.failed], ["创建时间", time(task.createdAt)],
            ["开始 / 结束", time(task.startedAt) + " / " + time(task.finishedAt), true]]);
        const channelValue = byId("result-channel").value;
        byId("result-channel").replaceChildren(new Option("全部渠道", ""));
        byId("channel-progress").replaceChildren();
        for (const channel of task.channels) {
            byId("result-channel").add(new Option(channel.channelName, channel.channelId));
            const item = document.createElement("div"); item.className = "channel-progress-item";
            item.textContent = channel.channelName + "：成功 " + channel.progress.succeeded + "，失败 " + channel.progress.failed + "，共 " + channel.progress.total + " 次";
            byId("channel-progress").append(item);
        }
        byId("result-channel").value = channelValue;
        frozenSnapshot = task.snapshot; byId("view-snapshot").disabled = !frozenSnapshot;
        byId("detail-refresh-note").textContent = active(task.status) ? "每 3 秒刷新进度，关闭页面不影响后台执行。" : task.status === "SUCCEEDED" ? "本轮已全部成功。" : "本轮已结束；可点击「重新发起」重做非成功结果，成功回答保留。";
        // 阅读子弹窗期间保留回答列表节点与滚动位置，关闭后继续刷新。
        if (!answerDialog.open && !informationDialog.open) await loadResults(id, revision);
        if (!detailDialog.open || taskId !== id || revision !== detailRevision) return;
        if (answerDialog.open && answerId != null && active(answerStatus) && !informationDialog.open) await loadAnswer(answerId, false);
        if (active(task.status)) detailTimer = setTimeout(() => refreshDetail().catch(detailRefreshFailed), 3000);
    }
    function detailRefreshFailed(error) {
        fail(error);
        if (detailDialog.open) detailTimer = setTimeout(() => refreshDetail().catch(detailRefreshFailed), 5000);
    }
    async function loadResults(id, revision) {
        const query = new URLSearchParams({page:resultPage, size:20});
        if (byId("result-status").value) query.set("status", byId("result-status").value);
        if (byId("result-channel").value) query.set("channelId", byId("result-channel").value);
        const result = await request("/diagnosis-tasks/" + id + "/results?" + query);
        if (!detailDialog.open || taskId !== id || revision !== detailRevision) return;
        const rows = byId("result-list"); rows.replaceChildren();
        if (!result.items.length) emptyRow(rows, 5, "当前筛选条件下暂无诊断结果。");
        for (const sample of result.items) {
            const row = rows.insertRow(); row.insertCell().textContent = (sample.questionSortOrder + 1) + ". " + sample.questionText;
            row.insertCell().textContent = sample.channelName + " · 第 " + sample.repeatIndex + " 次";
            row.insertCell().append(statusBadge(sample.status));
            row.insertCell().textContent = sample.durationMs == null ? "—" : sample.durationMs + " ms";
            row.insertCell().append(button("查看回答", () => loadAnswer(sample.id, true).catch(fail)));
        }
        byId("result-page-info").textContent = "第 " + (resultPage + 1) + " 页 · 共 " + result.total + " 条诊断结果";
        byId("result-previous").disabled = resultPage === 0;
        byId("result-next").disabled = (resultPage + 1) * 20 >= result.total;
    }
    async function loadAnswer(id, scroll) {
        const owner = taskId, revision = ++answerRevision;
        answerId = id;
        if (scroll) {
            answerStatus = null;
            byId("answer-content").textContent = "正在读取回答…";
            byId("answer-title").textContent = "回答详情";
            show("answer-error", "");
            if (!answerDialog.open) answerDialog.showModal();
        }
        const result = await request("/diagnosis-tasks/" + owner + "/results/" + id);
        if (!detailDialog.open || !answerDialog.open || informationDialog.open || taskId !== owner || answerId !== id || revision !== answerRevision) return;
        answerStatus = result.status;
        const content = byId("answer-content"); content.replaceChildren();
        byId("answer-title").textContent = result.channelName + " · 第 " + result.repeatIndex + " 次回答";
        const question = document.createElement("p"); question.textContent = result.questionText;
        const state = document.createElement("p"); state.append(statusBadge(result.status));
        const body = document.createElement("div"); body.className = "answer-body";
        body.textContent = result.status === "FAILED" ? result.errorMessage || "本次诊断失败" : result.answerText || "本次诊断尚未返回回答。";
        content.append(question, state, body);
        const info = document.createElement("p"); info.className = "help";
        info.textContent = "实际模型：" + (result.modelName || "接口未提供") + " · 耗时：" + (result.durationMs == null ? "—" : result.durationMs + " ms") + " · 结束原因：" + (result.finishReason || "接口未提供");
        content.append(info);
        if (result.status === "SUCCEEDED") {
            const heading = document.createElement("h4"); heading.textContent = "接口返回的引用"; content.append(heading);
            if (!result.citations?.length) {
                const none = document.createElement("p"); none.className = "help"; none.textContent = "接口未返回结构化引用。"; content.append(none);
            } else {
                const list = document.createElement("ul");
                for (const citation of result.citations) {
                    const item = document.createElement("li");
                    let url;
                    try { url = new URL(citation.url); } catch { url = null; }
                    if (url && ["http:", "https:"].includes(url.protocol)) {
                        const link = document.createElement("a"); link.href = url.href;
                        link.target = "_blank"; link.rel = "noopener noreferrer"; link.textContent = citation.title || citation.url; item.append(link);
                    } else { item.textContent = citation.title || citation.url || "引用信息"; }
                    list.append(item);
                }
                content.append(list);
            }
        }
        for (const [label, value] of [["查看 Token 用量", result.usageInfo], ["查看原始响应", result.rawResponse]]) {
            content.append(button(label, () => openInformation(label, value, label.includes("Token") ? "usage" : "raw")));
        }
        if (scroll) answerDialog.querySelector(".task-dialog-body").scrollTop = 0;
    }
    // 只读详情统一使用文本节点，模型响应及项目资料不会作为 HTML 执行。
    function displayValue(value) {
        if (value == null || value === "") return "未提供";
        if (Array.isArray(value)) return value.map(displayValue).join("、") || "未提供";
        if (typeof value === "object") return JSON.stringify(value, null, 2);
        return String(value);
    }
    function detailTable(title, headers, rows) {
        const section = document.createElement("section"); section.className = "readonly-section";
        const heading = document.createElement("h3"); heading.textContent = title;
        const wrap = document.createElement("div"); wrap.className = "table-scroll";
        const table = document.createElement("table"); table.className = "readonly-table";
        table.dataset.columns = headers.length;
        const head = table.createTHead().insertRow();
        headers.forEach(label => { const cell = document.createElement("th"); cell.scope = "col"; cell.textContent = label; head.append(cell); });
        const body = table.createTBody();
        rows.forEach(values => { const row = body.insertRow(); values.forEach(value => { row.insertCell().textContent = displayValue(value); }); });
        if (!rows.length) emptyRow(body, headers.length, "暂无数据");
        wrap.append(table); section.append(heading, wrap); return section;
    }
    // 快照展示实际冻结值；零值保留，不用当前可变配置替代历史配置。
    function renderSnapshot(content, snapshot) {
        const project = snapshot.project || {};
        content.append(detailTable("本轮概览", ["配置项", "冻结值"], [["诊断方案", snapshot.planName], ["每个问题重复执行次数", snapshot.repeatCount],
            ["问题数量", snapshot.questions?.length ?? 0], ["模型渠道数量", snapshot.channels?.length ?? 0]]));
        const projectFields = {name:"项目名称",websiteUrl:"官网",industry:"行业",coreFeatures:"核心功能",targetAudience:"目标用户",targetMarket:"目标市场",targetLanguage:"目标语言",sellingPoints:"主要卖点",pricing:"价格信息",aliases:"品牌别名",competitors:"竞争对手",keywords:"关键词"};
        content.append(detailTable("项目资料", ["资料项", "冻结值"], Object.entries(projectFields).map(([key, label]) => [label, project[key]])));
        const names = {MANUAL:"手动添加",GENERATED:"模型生成",NON_BRAND:"非品牌问题",BRAND:"品牌问题",COMPARISON:"对比",RECOMMENDATION:"推荐",PROBLEM_SOLVING:"解决问题",PURCHASE:"购买",TECHNICAL:"技术",AWARENESS:"认知"};
        content.append(detailTable("诊断问题", ["序号", "问题", "来源", "意图", "品牌范围", "语言"], (snapshot.questions || []).map((question, index) =>
            [index + 1, question.text, names[question.source] || question.source, names[question.intent] || question.intent, names[question.brandScope] || question.brandScope, question.language])));
        for (const channel of snapshot.channels || []) {
            content.append(detailTable("模型配置 · " + (channel.channelName || "未命名渠道"), ["配置项", "冻结值"], [
                ["渠道名称", channel.channelName], ["渠道编码", channel.channelCode], ["供应商", channel.providerCode], ["模型", channel.modelName],
                ["协议", {MOCK:"模拟响应",OPENAI_CHAT_COMPLETIONS:"Chat Completions 接口",OPENAI_RESPONSES:"Responses 接口"}[channel.protocolType] || channel.protocolType], ["策略 Bean", channel.strategyBeanName], ["接口地址", channel.baseUrl],
                ["随机性（temperature）", channel.temperature ?? "未设置，使用模型默认值"], ["最大输出 Token", channel.maxTokens ?? "未设置，使用模型默认值"]]));
        }
    }
    // 厂商用量字段保留完整路径，避免把缓存、推理等子项误算为总量。
    function usageRows(value, path = []) {
        const names = {prompt_tokens:"输入 Token（Chat）",input_tokens:"输入 Token（原生）",completion_tokens:"输出 Token（Chat）",output_tokens:"输出 Token（原生）",total_tokens:"总 Token",cached_tokens:"缓存命中 Token",reasoning_tokens:"推理 Token",audio_tokens:"音频 Token",text_tokens:"文本 Token",image_tokens:"图片 Token",usage_source:"用量来源",usage_semantic:"用量格式",claude_cache_creation_1_h_tokens:"写入缓存 Token（1 小时）",claude_cache_creation_5_m_tokens:"写入缓存 Token（5 分钟）",accepted_prediction_tokens:"接受预测 Token",rejected_prediction_tokens:"拒绝预测 Token",prompt_tokens_details:"输入明细（Chat）",input_tokens_details:"输入明细（原生）",completion_tokens_details:"输出明细（Chat）",output_tokens_details:"输出明细（原生）"};
        return Object.entries(value).flatMap(([key, item]) => {
            const parts = [...path, key];
            if (item && typeof item === "object" && !Array.isArray(item)) return usageRows(item, parts);
            return [[parts.map(part => names[part] || part).join(" / "), item, parts.join(".")]];
        });
    }
    function openInformation(title, value, type) {
        const content = byId("information-content"); content.replaceChildren();
        byId("information-title").textContent = title;
        byId("information-note").textContent = type === "snapshot" ? "只读 · 创建任务时冻结的配置，后续修改不会改变本轮数据。" : type === "usage" ? "只读 · 按模型接口实际返回展示，子项不重复计入总量。" : "只读 · 服务端保存的原始响应，JSON 已格式化。";
        let parsed = value;
        if (typeof value === "string") { try { parsed = JSON.parse(value); } catch { /* 非 JSON 响应保留原文。 */ } }
        if (parsed == null) { const empty = document.createElement("p"); empty.className = "help"; empty.textContent = "接口未提供此信息。"; content.append(empty); }
        else if (type === "snapshot") renderSnapshot(content, parsed);
        else if (type === "usage" && typeof parsed === "object" && !Array.isArray(parsed)) content.append(detailTable("Token 用量明细", ["指标", "用量", "接口字段"], usageRows(parsed)));
        else { const pre = document.createElement("pre"); pre.className = "response-json"; pre.textContent = typeof parsed === "string" ? parsed : JSON.stringify(parsed, null, 2); content.append(pre); }
        informationDialog.showModal(); content.scrollTop = 0;
    }
    byId("new-task").addEventListener("click", () => openCreate().catch(fail));
    byId("restart-task").addEventListener("click", async () => {
        if (restarting || taskId == null) return;
        const id = taskId;
        restarting = true; byId("restart-task").disabled = true; show("detail-error", "");
        try {
            await request("/diagnosis-tasks/" + id + "/restart", {method:"POST"});
            show("task-success", "任务 #" + id + " 的非成功结果已重新排队，成功回答保留。");
            if (detailDialog.open && taskId === id) {
                resultPage = 0; byId("result-status").value = "";
                await refreshDetail();
            }
            await loadList();
        } catch (error) {
            if (detailDialog.open && taskId === id) show("detail-error", error.message);
            else fail(error);
        } finally {
            restarting = false; byId("restart-task").disabled = false;
        }
    });
    byId("create-plan").addEventListener("change", () => loadPreview().catch(error => show("create-error", error.message)));
    byId("create-task-form").addEventListener("submit", createTask);
    byId("close-create").addEventListener("click", () => { if (!creating) createDialog.close(); });
    createDialog.addEventListener("cancel", event => { if (creating) event.preventDefault(); });
    byId("close-detail").addEventListener("click", () => detailDialog.close());
    for (const dialog of [createDialog, detailDialog, answerDialog, informationDialog]) {
        dialog.addEventListener("click", event => {
            const rect = dialog.getBoundingClientRect();
            if (event.target === dialog && (event.clientX < rect.left || event.clientX > rect.right || event.clientY < rect.top || event.clientY > rect.bottom) && !creating) dialog.close();
        });
        dialog.addEventListener("close", () => {
            if (dialog === detailDialog) { informationDialog.close(); answerDialog.close(); clearTimeout(detailTimer); detailRevision++; answerRevision++; taskId = null; answerId = null; }
            if (dialog === answerDialog) { informationDialog.close(); answerId = null; answerRevision++; }
            if (dialog === createDialog) previewRevision++;
            document.body.classList.toggle("task-dialog-open", [createDialog, detailDialog, answerDialog, informationDialog].some(item => item.open));
        });
    }
    byId("close-answer").addEventListener("click", () => answerDialog.close());
    byId("close-information").addEventListener("click", () => informationDialog.close());
    byId("view-snapshot").addEventListener("click", () => openInformation("本轮冻结配置", frozenSnapshot, "snapshot"));
    byId("task-filters").addEventListener("submit", event => { event.preventDefault(); page = 0; show("task-error", ""); loadList().catch(fail); });
    byId("task-previous").addEventListener("click", () => { if (page > 0) page--; loadList().catch(fail); });
    byId("task-next").addEventListener("click", () => { page++; loadList().catch(fail); });
    byId("result-filters").addEventListener("submit", event => { event.preventDefault(); resultPage = 0; refreshDetail().catch(detailRefreshFailed); });
    byId("result-previous").addEventListener("click", () => { if (resultPage > 0) resultPage--; refreshDetail().catch(detailRefreshFailed); });
    byId("result-next").addEventListener("click", () => { resultPage++; refreshDetail().catch(detailRefreshFailed); });
    window.addEventListener("pagehide", () => { clearTimeout(listTimer); clearTimeout(detailTimer); detailRevision++; answerRevision++; });
    Promise.all([loadOptions(), loadList()]).catch(fail);
})();
