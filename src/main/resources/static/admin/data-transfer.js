"use strict";
/** 项目文件迁移页面：分页选择项目，下载 JSON，导入后展示新项目及数据数量；请求不自动重试。 */
(() => {
    const byId = id => document.getElementById(id);
    const maxFileBytes = 50 * 1024 * 1024;
    let page = 0;
    let total = 0;
    let busy = false;
    let loadSequence = 0;

    /** 展示纯文本提示，不将文件内容、项目名称或服务错误作为 HTML 插入。 */
    function notice(id, text = "") {
        byId(id).textContent = text;
        byId(id).hidden = !text;
    }

    /** 迁移期间禁用页面操作，防止双击重复创建项目；网络失败后由管理员决定是否再提交。 */
    function setBusy(value) {
        busy = value;
        document.querySelectorAll("main button, main input").forEach(control => { control.disabled = value; });
        byId("previous-page").disabled = value || page === 0;
        byId("next-page").disabled = value || (page + 1) * 20 >= total;
    }

    /** 查询项目分页；序号防止先发后到的旧查询覆盖新结果。 */
    async function loadProjects() {
        const sequence = ++loadSequence;
        notice("transfer-error");
        try {
            const query = new URLSearchParams({page: String(page), size: "20", keyword: byId("keyword").value.trim()});
            const result = await AdminHttp.requestJson("/projects?" + query);
            if (sequence !== loadSequence) { return; }
            total = result.total;
            byId("project-rows").replaceChildren();
            result.items.forEach(project => {
                const row = document.createElement("tr");
                [String(project.id), project.name].forEach(text => {
                    const cell = document.createElement("td");
                    cell.textContent = text;
                    row.append(cell);
                });
                const cell = document.createElement("td");
                const button = document.createElement("button");
                button.type = "button";
                button.className = "text-button";
                button.textContent = "导出项目";
                button.addEventListener("click", () => exportProject(project.id));
                cell.append(button);
                row.append(cell);
                byId("project-rows").append(row);
            });
            byId("project-page").textContent = `第 ${page + 1} 页，共 ${total} 个项目`;
            if (!result.items.length) { byId("project-page").textContent = "没有符合条件的项目"; }
            setBusy(busy);
        } catch (error) {
            if (sequence === loadSequence) { notice("transfer-error", error.message); }
        }
    }

    /** 成功响应转成下载文件，错误沿用 ProblemDetail 提示；立即释放临时对象 URL。 */
    async function exportProject(projectId) {
        if (busy) { return; }
        setBusy(true);
        notice("transfer-error");
        notice("transfer-status", "正在整理项目数据，请稍候…");
        let url;
        try {
            const archive = await AdminHttp.requestJson("/project-transfers/" + encodeURIComponent(projectId));
            url = URL.createObjectURL(new Blob([JSON.stringify(archive, null, 2)], {type: "application/json;charset=utf-8"}));
            const link = document.createElement("a");
            link.href = url;
            link.download = `project-${projectId}.json`;
            document.body.append(link);
            link.click();
            link.remove();
            notice("transfer-status", "项目文件已生成并开始下载。");
        } catch (error) {
            notice("transfer-status");
            notice("transfer-error", error.message);
        } finally {
            if (url) { setTimeout(() => URL.revokeObjectURL(url), 1000); }
            setBusy(false);
        }
    }

    /** 上传原始文件文本，不在浏览器解析后重写 ID；成功后展示新项目，重复提交会再建副本。 */
    async function importProject(event) {
        event.preventDefault();
        if (busy) { return; }
        const file = byId("project-file").files[0];
        notice("transfer-error");
        if (!file || !file.size || file.size > maxFileBytes) {
            notice("transfer-error", "请选择非空且不超过 50 MiB 的 JSON 文件");
            return;
        }
        setBusy(true);
        byId("import-result").hidden = true;
        notice("transfer-status", "正在校验渠道并导入项目，请勿重复提交…");
        try {
            const response = await AdminHttp.requestJson("/project-transfers", {method: "POST", body: await file.text()});
            const labels = {project: "项目", plans: "方案", questions: "问题", planChannels: "方案渠道", tasks: "任务", results: "诊断结果", reports: "报告", analyses: "回答分析"};
            byId("import-result").textContent = `已导入项目“${response.projectName}”（新项目 ID：${response.projectId}）。` +
                Object.entries(labels).map(([key, label]) => `${label} ${response.counts[key]} 条`).join("，") + "。";
            byId("import-result").hidden = false;
            byId("project-file").value = "";
            notice("transfer-status", "导入完成，历史记录不会自动执行。");
            page = 0;
            await loadProjects();
        } catch (error) {
            notice("transfer-status");
            notice("transfer-error", error.message + (error.status ? "" : "。网络中断时请先查看项目列表，确认是否已导入，再决定是否重试"));
        } finally {
            setBusy(false);
        }
    }

    byId("project-search").addEventListener("submit", event => { event.preventDefault(); page = 0; loadProjects(); });
    byId("previous-page").addEventListener("click", () => { if (page > 0) { page--; loadProjects(); } });
    byId("next-page").addEventListener("click", () => { if ((page + 1) * 20 < total) { page++; loadProjects(); } });
    byId("project-import").addEventListener("submit", importProject);
    loadProjects();
})();
