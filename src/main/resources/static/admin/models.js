"use strict";
(() => {
    const version = "20261014-protocol1", error = document.getElementById("model-error");
    const showError = message => { error.textContent = message; error.hidden = !message; };
    async function request(path, options = {}) {
        const response = await fetch(path, {cache: "no-store", headers: {"Content-Type": "application/json"}, ...options});
        let body;
        try { body = await response.json(); } catch { throw new Error("服务返回异常，请稍后重试"); }
        if (!response.ok) throw new Error(body.detail || "模型配置操作失败，请稍后重试");
        return body;
    }
    const form = document.getElementById("model-form"), rawId = new URLSearchParams(location.search).get("id");
    if (form) {
        const button = document.getElementById("save-model");
        const id = rawId && /^[1-9]\d*$/.test(rawId) ? rawId : null;
        if (rawId && !id) { showError("渠道 ID 无效"); button.disabled = true; return; }
        const syncStrategy = () => {
            const option = form.elements.strategyBeanName.selectedOptions[0];
            const protocol = option?.dataset.protocolType || "";
            const remote = protocol !== "MOCK";
            for (const key of ["baseUrl", "modelName"]) form.elements[key].required = remote;
            // 留空不发送随机性，填写由远端模型校验；仅保留明确不使用参数的策略限制。
            const searchModel = option?.value === "chatCompletionsApiOpenAiStrategy"
                && form.elements.modelName.value.trim() === "gpt-5-search-api";
            const generationParameters = Boolean(protocol) && remote && !searchModel;
            form.elements.temperature.disabled = !generationParameters;
            form.elements.temperature.required = false;
            form.elements.maxTokens.disabled = !generationParameters;
            form.elements.maxTokens.required = generationParameters;
            document.getElementById("generation-help").textContent = !generationParameters
                ? "当前策略或模型不发送这些生成参数，因此不提供编辑。"
                : "随机性可留空，留空不发送；填写则由模型接口校验。输出上限会随请求发送。"
                    + (protocol === "OPENAI_RESPONSES" ? "输出上限使用 max_output_tokens，可能包含推理 token。" : "");
            document.getElementById("protocol-help").textContent = "策略 Bean：" + (option?.value || "请选择调用策略") + " · 接口协议：" + (protocol || "—")
                + "。策略决定请求参数，模型和工具权限仍需实际验证。";
        };
        form.elements.strategyBeanName.addEventListener("change", syncStrategy);
        form.elements.modelName.addEventListener("input", syncStrategy);
        button.disabled = true;
        Promise.all([request("/llm-channels/strategies"), id ? request("/llm-channels/" + id) : Promise.resolve(null)])
            .then(([strategies, channel]) => {
                const select = form.elements.strategyBeanName;
                select.replaceChildren();
                for (const strategy of strategies) {
                    const option = document.createElement("option");
                    option.value = strategy.beanName; option.textContent = strategy.beanName + " · " + strategy.protocolType;
                    option.dataset.protocolType = strategy.protocolType;
                    select.append(option);
                }
                select.value = "chatCompletionsApiCommonStrategy";
                if (!channel) { syncStrategy(); button.disabled = false; return; }
                document.getElementById("model-heading").textContent = "编辑模型配置";
                for (const element of form.elements) {
                    if (!element.name || !(element.name in channel)) continue;
                    if (element.type === "checkbox") element.checked = Boolean(channel[element.name]);
                    else element.value = channel[element.name] ?? "";
                }
                document.getElementById("key-status").textContent = channel.keyConfigured ? "已保存密钥，留空保留；填写新密钥可替换。" : "尚未保存密钥。";
                form.elements.channelCode.readOnly = true;
                syncStrategy(); button.disabled = false;
            }).catch(failure => showError(failure.message));
        form.addEventListener("submit", async event => {
            event.preventDefault(); if (button.disabled) return;
            button.disabled = true; button.textContent = "正在保存…"; showError("");
            const channel = Object.fromEntries(new FormData(form));
            // 禁用字段不在 FormData 中，不把缺失值转换为 NaN 或 null。
            for (const key of ["score", "maxTokens"]) {
                if (key in channel) channel[key] = Number(channel[key]);
            }
            channel.temperature = "temperature" in channel && channel.temperature.trim() !== ""
                ? Number(channel.temperature) : null;
            channel.enabled = form.elements.enabled.checked;
            try {
                const saved = await request(id ? "/llm-channels/" + id : "/llm-channels",
                    {method: id ? "PUT" : "POST", body: JSON.stringify(channel)});
                location.href = "./models.html?v=" + version + "&saved=" + saved.id;
            } catch (failure) { showError(failure.message); button.disabled = false; button.textContent = "保存模型配置"; }
        });
        return;
    }
    let page = 0, keyword = "", busy = false;
    const previous = document.getElementById("previous-page"), next = document.getElementById("next-page");
    const rows = document.getElementById("model-rows");
    async function load() {
        busy = true; previous.disabled = true; next.disabled = true; showError("");
        try {
            const result = await request("/llm-channels?page=" + page + "&size=20&keyword=" + encodeURIComponent(keyword));
            rows.replaceChildren();
            for (const c of result.items) {
                const row = document.createElement("tr");
                const values = [c.id, c.channelName, c.channelCode, c.providerCode, c.modelName || "本地模拟", c.strategyBeanName, c.protocolType || "策略未注册", c.score, c.enabled ? "已启用" : "已停用"];
                for (const value of values) {
                    const cell = document.createElement("td"); cell.textContent = value;
                    row.append(cell);
                }
                const cell = document.createElement("td"), edit = document.createElement("a"), toggle = document.createElement("button");
                edit.className = "text-button"; edit.textContent = "编辑"; edit.href = "./model-config.html?v=" + version + "&id=" + c.id;
                toggle.className = "text-button"; toggle.type = "button"; toggle.textContent = c.enabled ? "停用" : "启用";
                toggle.addEventListener("click", async () => {
                    if (toggle.disabled) return; toggle.disabled = true; showError("");
                    try { await request("/llm-channels/" + c.id + "/enabled", {method: "PATCH", body: JSON.stringify({enabled: !c.enabled})}); await load(); }
                    catch (failure) { showError(failure.message); toggle.disabled = false; }
                });
                cell.append(edit, toggle); row.append(cell); rows.append(row);
            }
            if (!result.items.length) {
                const row = document.createElement("tr"), cell = document.createElement("td");
                cell.colSpan = 10; cell.className = "table-message"; cell.textContent = keyword ? "没有匹配的模型渠道。" : "暂无模型渠道，点击“新增模型”添加配置。"; row.append(cell); rows.append(row);
            }
            document.getElementById("model-total").textContent = "共 " + result.total + " 个渠道 · 按优先级降序排列";
            document.getElementById("model-page").textContent = "第 " + (page + 1) + " 页";
            previous.disabled = page === 0; next.disabled = (page + 1) * 20 >= result.total;
        } catch (failure) { showError(failure.message); document.getElementById("model-total").textContent = "加载失败"; }
        finally { busy = false; }
    }
    document.getElementById("model-search").addEventListener("submit", event => { event.preventDefault(); if (!busy) { keyword = document.getElementById("keyword").value.trim(); page = 0; load(); } });
    previous.addEventListener("click", () => { if (!busy && page > 0) { page--; load(); } });
    next.addEventListener("click", () => { if (!busy) { page++; load(); } });
    const saved = new URLSearchParams(location.search).get("saved");
    if (/^[1-9]\d*$/.test(saved || "")) { const notice = document.getElementById("model-success"); notice.textContent = "模型配置已保存，渠道 ID：" + saved; notice.hidden = false; }
    load();
})();
