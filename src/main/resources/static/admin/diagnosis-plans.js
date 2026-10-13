"use strict";
(() => {
    const byId = id => document.getElementById(id);
    const form = byId("plan-form");
    const editor = byId("editor");
    const questionPageSize = 20;
    let questionPage = 0;
    let viewOnly = false;
    const intentLabels = {RECOMMENDATION:"产品推荐", PROBLEM_SOLVING:"问题解决", COMPARISON:"产品比较", PURCHASE:"采购选型", TECHNICAL:"技术能力", AWARENESS:"品牌认知"};
    const scopeLabels = {NON_BRAND:"非品牌发现", BRAND:"品牌认知", COMPARISON:"竞品比较"};
    let planId = null, page = 0, questions = [], candidates = [], channels = [], ready = false, revision = 0, generating = false, saving = false, dirty = false;
    const message = (id, text) => {
        const target = byId(editor.open ? "editor-" + id : id);
        for (const element of [byId(id), byId("editor-" + id)]) {
            element.textContent = ""; element.hidden = true;
        }
        target.textContent = text; target.hidden = !text;
        return target;
    };
    const error = text => {
        const target = message("plan-error", text);
        if (text) target.scrollIntoView({behavior:"smooth", block:"center"});
    };
    const normalize = text => text.normalize("NFKC").toLocaleLowerCase().replace(/\s+/g, "");
    const markDirty = () => { dirty = true; message("plan-success", ""); };
    async function request(path, options = {}) {
        const response = await fetch(path, {cache:"no-store", headers:{"Content-Type":"application/json"}, ...options});
        let result;
        try { result = await response.json(); } catch { throw new Error("服务响应异常，请重试"); }
        if (!response.ok) throw new Error(result.detail || "操作失败，请检查输入或服务状态");
        return result;
    }
    function button(text, action, className = "text-button") {
        const element = document.createElement("button");
        element.type = "button"; element.className = className; element.textContent = text;
        element.addEventListener("click", action);
        return element;
    }
    // 下拉选项读取完整分页结果，不能把第一页误认为全部项目或渠道。
    async function allItems(path) {
        const items = [];
        for (let current = 0; ; current++) {
            const result = await request(path + "?page=" + current + "&size=100");
            items.push(...result.items);
            if (items.length >= result.total || !result.items.length) return items;
        }
    }
    async function loadOptions() {
        const [projects, models] = await Promise.all([allItems("/projects"), allItems("/llm-channels")]);
        channels = models;
        form.elements.projectId.replaceChildren(new Option("请选择项目", ""));
        projects.forEach(project => form.elements.projectId.add(new Option(project.name, project.id)));
        byId("generation-channel").replaceChildren(new Option("按数据库优先级选择", ""));
        byId("test-channels").replaceChildren();
        for (const channel of channels) {
            const label = document.createElement("label");
            label.className = "channel-option";
            const checkbox = document.createElement("input");
            checkbox.type = "checkbox"; checkbox.value = channel.id; checkbox.disabled = !channel.enabled;
            checkbox.addEventListener("change", () => { markDirty(); updateCount(); });
            const copy = document.createElement("span");
            copy.textContent = channel.channelName + (channel.enabled ? "" : "（已停用）");
            const detail = document.createElement("small");
            detail.textContent = (channel.modelName || "本地模拟") + " · " + channel.strategyName;
            copy.append(detail); label.append(checkbox, copy); byId("test-channels").append(label);
            if (channel.enabled) byId("generation-channel").add(new Option(channel.channelName, channel.channelCode));
        }
        if (!projects.length || !channels.some(channel => channel.enabled))
            message("plan-success", "请先创建项目并启用模型渠道，再保存诊断方案。");
        ready = true; byId("new-plan").disabled = false;
    }
    async function loadList() {
        const result = await request("/diagnosis-plans?page=" + page + "&size=10&keyword=" + encodeURIComponent(byId("keyword").value.trim()));
        const rows = byId("plan-list"); rows.replaceChildren();
        if (!result.items.length) {
            const row = rows.insertRow(), cell = row.insertCell();
            cell.colSpan = 6; cell.className = "table-message"; cell.textContent = "暂无方案，点击「创建方案」建立问题集合。";
        }
        for (const plan of result.items) {
            const row = rows.insertRow();
            [plan.id, plan.name, plan.projectName || "项目不可用", plan.repeatCount + " 次", plan.updatedAt?.replace("T", " ") || "—"].forEach(value => {
                row.insertCell().textContent = value;
            });
            row.insertCell().append(
                button("查看", () => openPlan(plan.id, true).catch(exception => error(exception.message))),
                button("编辑", () => openPlan(plan.id).catch(exception => error(exception.message))));
        }
        byId("page-info").textContent = "第 " + (page + 1) + " 页 · 共 " + result.total + " 个方案";
        byId("previous-page").disabled = page === 0;
        byId("next-page").disabled = (page + 1) * 10 >= result.total;
    }
    function canDiscard() {
        if (!dirty) return true;
        byId("discard-prompt").hidden = false;
        byId("keep-editing").focus();
        return false;
    }
    async function openPlan(id = null, readonly = false) {
        if (!ready || saving || !canDiscard()) return;
        // 详情成功回读后再替换编辑状态，加载失败保留当前输入。
        const detail = id ? await request("/diagnosis-plans/" + id) : null;
        viewOnly = readonly;
        revision++; planId = id; dirty = false; candidates = []; questions = detail ? detail.questions.map(question => ({...question})) : [];
        questionPage = 0;
        form.reset(); error(""); message("plan-success", "");
        byId("candidates-panel").hidden = true;
        byId("discard-prompt").hidden = true;
        byId("generation-status").textContent = "结合用户意图、使用场景和侧面需求生成。";
        byId("editor-title").textContent = id ? (viewOnly ? "查看方案 #" : "编辑方案 #") + id : "创建方案";
        byId("editor-description").textContent = viewOnly ? "展示已保存的配置和问题集合。" : "生成候选供审核，确认后的问题才会保存到方案。";
        byId("plan-config").hidden = viewOnly;
        byId("generator").hidden = viewOnly;
        byId("add-question").hidden = viewOnly;
        byId("save-plan").hidden = viewOnly;
        const overview = byId("plan-overview");
        overview.hidden = !viewOnly;
        overview.replaceChildren();
        if (viewOnly) {
            for (const [label, value] of [["方案名称", detail.name], ["关联项目", detail.projectName || "项目不可用"],
                ["每题每渠道重复次数", detail.repeatCount + " 次"], ["被测模型渠道", detail.channelIds.map(id =>
                    channels.find(channel => channel.id === id)?.channelName || "不可用渠道 #" + id).join("、")]]) {
                const item = document.createElement("div");
                item.className = "overview-item";
                if (label === "被测模型渠道") item.classList.add("overview-channels");
                const caption = document.createElement("span");
                caption.className = "overview-label"; caption.textContent = label;
                const content = document.createElement("strong");
                content.textContent = value;
                item.append(caption, content); overview.append(item);
            }
        }
        if (detail) {
            form.elements.name.value = detail.name; form.elements.projectId.value = detail.projectId;
            form.elements.repeatCount.value = detail.repeatCount;
        }
        for (const checkbox of byId("test-channels").querySelectorAll("input")) {
            checkbox.checked = detail?.channelIds.includes(Number(checkbox.value)) || false;
            checkbox.disabled = !channels.find(channel => channel.id === Number(checkbox.value))?.enabled;
        }
        if (!editor.open) editor.showModal();
        if (!viewOnly && detail && detail.channelIds.some(id => !channels.some(channel => channel.id === id && channel.enabled)))
            error("方案包含已停用或不可用的测试渠道，请取消勾选并重新选择。");
        // 停用的已选项允许取消，不能重新作为新渠道勾选。
        for (const checkbox of byId("test-channels").querySelectorAll("input")) {
            if (checkbox.disabled && checkbox.checked) {
                checkbox.disabled = false;
                checkbox.addEventListener("change", () => { if (!checkbox.checked) checkbox.disabled = true; }, {once:true});
            }
        }
        byId("generation-language").value = questions[0]?.language || "中文";
        renderQuestions(); updateCount();
        document.body.classList.add("plan-dialog-open");
        editor.querySelector(".dialog-body").scrollTop = 0;
        (viewOnly ? byId("close-editor") : form.elements.name).focus({preventScroll:true});
    }
    function labeledSelect(title, choices, selected, onChange) {
        const label = document.createElement("label"); label.textContent = title;
        const select = document.createElement("select");
        Object.entries(choices).forEach(([value, text]) => select.add(new Option(text, value)));
        select.value = selected; select.setAttribute("aria-label", title);
        select.addEventListener("change", () => { onChange(select.value); markDirty(); });
        label.append(select); return label;
    }
    // 分页仅控制展示，完整集合一直保留在内存中，保存时一起提交。
    function questionTable(items, offset = 0, candidate = false) {
        const table = document.createElement("table"); table.className = "question-table";
        if (candidate) table.classList.add("candidate-table");
        const head = table.createTHead().insertRow();
        const titles = ["序号", "问题内容", "来源", "提问意图", "品牌范围", "语言"];
        if (candidate) titles.unshift("选用");
        else if (!viewOnly) titles.push("操作");
        titles.forEach(title => {
            const cell = document.createElement("th"); cell.scope = "col"; cell.textContent = title; head.append(cell);
        });
        const body = table.createTBody();
        items.forEach((question, position) => {
            const index = offset + position, row = body.insertRow(), prefix = (candidate ? "候选" : "问题") + (index + 1);
            if (candidate) {
                const check = document.createElement("input");
                check.type = "checkbox"; check.checked = true; check.dataset.candidateIndex = index;
                check.setAttribute("aria-label", "候选 " + (index + 1)); row.insertCell().append(check);
            }
            row.insertCell().textContent = index + 1;
            if (viewOnly && !candidate) {
                [question.text, question.source === "GENERATED" ? "模型生成" : "人工维护",
                    intentLabels[question.intent], scopeLabels[question.brandScope], question.language]
                    .forEach(value => { row.insertCell().textContent = value; });
                return;
            }
            const textarea = document.createElement("textarea");
            textarea.rows = 2; textarea.value = question.text; textarea.maxLength = 2000; textarea.required = !candidate;
            textarea.setAttribute("aria-label", prefix + "内容");
            textarea.addEventListener("input", () => { question.text = textarea.value; if (!candidate) markDirty(); });
            row.insertCell().append(textarea);
            row.insertCell().textContent = question.source === "GENERATED" ? "模型生成" : "人工维护";
            for (const [key, title, choices] of [["intent", "提问意图", intentLabels], ["brandScope", "品牌范围", scopeLabels]]) {
                const select = labeledSelect(title, choices, question[key], value => question[key] = value).querySelector("select");
                select.setAttribute("aria-label", prefix + title); row.insertCell().append(select);
            }
            const language = document.createElement("input");
            language.value = question.language; language.maxLength = 100; language.required = !candidate;
            language.setAttribute("aria-label", prefix + "语言");
            language.addEventListener("input", () => { question.language = language.value; if (!candidate) markDirty(); });
            row.insertCell().append(language);
            if (!candidate) row.insertCell().append(button("删除", () => { questions.splice(index, 1); markDirty(); renderQuestions(); }));
        });
        return table;
    }
    function renderQuestions() {
        const list = byId("question-list"); list.replaceChildren();
        const pages = Math.max(1, Math.ceil(questions.length / questionPageSize));
        questionPage = Math.min(questionPage, pages - 1);
        const offset = questionPage * questionPageSize;
        if (questions.length) list.append(questionTable(questions.slice(offset, offset + questionPageSize), offset));
        else {
            const empty = document.createElement("p"); empty.className = "help"; empty.textContent = "集合为空。可生成候选或添加自定义问题。"; list.append(empty);
        }
        byId("question-pagination").hidden = !questions.length;
        byId("question-page-info").textContent = "每页 20 条 · " + (offset + 1) + "–" + Math.min(offset + questionPageSize, questions.length) + " / " + questions.length + " 条";
        byId("question-page").replaceChildren();
        for (let index = 0; index < pages; index++) byId("question-page").add(new Option("第 " + (index + 1) + " / " + pages + " 页", index));
        byId("question-page").value = questionPage;
        byId("question-previous").disabled = questionPage === 0;
        byId("question-next").disabled = questionPage === pages - 1;
        updateCount();
    }
    function updateCount() {
        const selected = [...byId("test-channels").querySelectorAll("input:checked")].length;
        byId("question-count").textContent = questions.length + " / 200";
        byId("sample-count").textContent = "预计 " + (questions.length * selected * (Number(form.elements.repeatCount.value) || 0)) + " 次采样";
    }
    byId("generate").addEventListener("click", async () => {
        if (generating || saving) return;
        const projectId = Number(form.elements.projectId.value), count = Number(byId("generation-count").value), language = byId("generation-language").value.trim();
        if (!projectId || !Number.isInteger(count) || count < 1 || count > 30 || !language) { error("请先选择项目，并填写 1 到 30 的生成数量及语言"); return; }
        const startedRevision = revision;
        generating = true; byId("generate").disabled = true; error("");
        byId("generation-status").textContent = "正在生成候选问题，请稍候…";
        try {
            const result = await request("/diagnosis-plans/generate-questions", {method:"POST", body:JSON.stringify({projectId, count, language, channelCode:byId("generation-channel").value || null})});
            if (revision !== startedRevision) return;
            candidates = result; byId("candidate-list").replaceChildren();
            byId("candidate-list").append(questionTable(candidates, 0, true));
            byId("candidates-panel").hidden = false;
            byId("generation-status").textContent = "已生成 " + candidates.length + " 个候选，请审核后加入集合。";
        } catch (exception) {
            if (revision === startedRevision) {
                error(exception.message);
                byId("generation-status").textContent = exception.message;
            }
        } finally { generating = false; byId("generate").disabled = false; }
    });
    byId("add-candidates").addEventListener("click", () => {
        const selected = [...byId("candidate-list").querySelectorAll("input:checked")].map(input => candidates[Number(input.dataset.candidateIndex)]);
        const existing = new Set(questions.map(question => normalize(question.text)));
        const added = [];
        for (const candidate of selected) {
            if (!candidate.text.trim() || !candidate.language.trim()) { error("候选内容和语言不能为空"); return; }
            const key = normalize(candidate.text);
            if (!existing.has(key)) { added.push({...candidate}); existing.add(key); }
        }
        if (questions.length + added.length > 200) { error("每个方案最多 200 个问题"); return; }
        if (added.length) questionPage = Math.floor(questions.length / questionPageSize);
        questions.push(...added); markDirty(); renderQuestions(); error("");
        byId("generation-status").textContent = "本次加入 " + added.length + " 个问题；重复内容已跳过。";
        byId("candidates-panel").hidden = true; candidates = [];
    });
    byId("add-question").addEventListener("click", () => {
        if (questions.length >= 200) { error("每个方案最多 200 个问题"); return; }
        questions.push({text:"", source:"MANUAL", intent:"RECOMMENDATION", brandScope:"NON_BRAND", language:byId("generation-language").value.trim() || "中文"});
        questionPage = Math.floor((questions.length - 1) / questionPageSize);
        markDirty(); renderQuestions(); byId("question-list").querySelector("tbody tr:last-child textarea").focus();
    });
    form.addEventListener("submit", async event => {
        event.preventDefault(); error("");
        if (saving || viewOnly) return;
        const invalidIndex = questions.findIndex(question => !question.text.trim() || !question.language.trim());
        if (invalidIndex >= 0) {
            questionPage = Math.floor(invalidIndex / questionPageSize);
            renderQuestions(); error("问题 " + (invalidIndex + 1) + " 的内容和语言不能为空"); return;
        }
        const channelIds = [...byId("test-channels").querySelectorAll("input:checked")].map(input => Number(input.value));
        if (!questions.length || !channelIds.length) { error("请至少添加一个问题并选择一个被测渠道"); return; }
        saving = true; revision++;
        const controls = [...form.querySelectorAll("input, select, textarea, button")];
        const disabledStates = controls.map(control => control.disabled);
        controls.forEach(control => control.disabled = true);
        try {
            const saved = await request(planId ? "/diagnosis-plans/" + planId : "/diagnosis-plans", {method:planId ? "PUT" : "POST",
                body:JSON.stringify({name:form.elements.name.value.trim(), projectId:Number(form.elements.projectId.value), repeatCount:Number(form.elements.repeatCount.value), channelIds, questions})});
            planId = saved.id; questions = saved.questions; dirty = false; revision++;
            byId("discard-prompt").hidden = true;
            editor.close();
            message("plan-success", "方案已保存，ID：" + planId + "。尚未执行诊断采样。")
                .scrollIntoView({behavior:"smooth", block:"center"});
            try { await loadList(); }
            catch (exception) { error("方案已保存，但列表刷新失败：" + exception.message); }
        } catch (exception) { error(exception.message); }
        finally {
            controls.forEach((control, index) => control.disabled = disabledStates[index]);
            saving = false; byId("generate").disabled = generating;
        }
    });
    form.elements.projectId.addEventListener("change", () => {
        revision++; candidates = []; byId("candidates-panel").hidden = true; markDirty();
        byId("generation-status").textContent = "项目已切换，请确认现有问题是否适用于新项目。";
    });
    form.elements.name.addEventListener("input", markDirty);
    form.elements.repeatCount.addEventListener("input", () => { markDirty(); updateCount(); });
    byId("new-plan").disabled = true;
    byId("question-previous").addEventListener("click", () => { questionPage--; renderQuestions(); });
    byId("question-next").addEventListener("click", () => { questionPage++; renderQuestions(); });
    byId("question-page").addEventListener("change", event => { questionPage = Number(event.target.value); renderQuestions(); });
    byId("new-plan").addEventListener("click", () => openPlan().catch(exception => error(exception.message)));
    function closeEditor() {
        if (saving || !canDiscard()) return;
        editor.close();
    }
    byId("close-editor").addEventListener("click", closeEditor);
    editor.addEventListener("cancel", event => {
        event.preventDefault();
        if (!byId("discard-prompt").hidden) byId("discard-prompt").hidden = true;
        else closeEditor();
    });
    byId("keep-editing").addEventListener("click", () => {
        byId("discard-prompt").hidden = true;
        form.elements.name.focus({preventScroll:true});
    });
    byId("discard-changes").addEventListener("click", () => {
        if (saving) return;
        dirty = false; editor.close();
    });
    editor.addEventListener("click", event => {
        const bounds = editor.getBoundingClientRect();
        const outside = event.clientX < bounds.left || event.clientX > bounds.right
            || event.clientY < bounds.top || event.clientY > bounds.bottom;
        if (event.target === editor && outside) closeEditor();
    });
    editor.addEventListener("close", () => {
        document.body.classList.remove("plan-dialog-open");
        byId("discard-prompt").hidden = true;
        dirty = false; revision++;
    });
    byId("search-form").addEventListener("submit", event => { event.preventDefault(); page = 0; loadList().catch(exception => error(exception.message)); });
    byId("previous-page").addEventListener("click", () => { page--; loadList().catch(exception => error(exception.message)); });
    byId("next-page").addEventListener("click", () => { page++; loadList().catch(exception => error(exception.message)); });
    window.addEventListener("beforeunload", event => { if (dirty) { event.preventDefault(); event.returnValue = ""; } });
    Promise.all([loadOptions(), loadList()]).catch(exception => error(exception.message));
})();
