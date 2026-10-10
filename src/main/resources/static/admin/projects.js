"use strict";
(() => {
    const version = "20261010-project1";
    const error = document.getElementById("project-error");
    const showError = message => { error.textContent = message; error.hidden = !message; };
    async function request(path, options = {}) {
        const response = await fetch(path, {cache: "no-store", headers: {"Content-Type": "application/json"}, ...options});
        const body = await response.json();
        if (!response.ok) throw new Error(body.detail || "项目操作失败，请稍后重试");
        return body;
    }
    const form = document.getElementById("project-form");
    if (form) {
        form.addEventListener("submit", async event => {
            event.preventDefault();
            const button = document.getElementById("create-project");
            if (button.disabled) return;
            showError(""); button.disabled = true; button.textContent = "正在创建…";
            const project = Object.fromEntries(new FormData(form));
            for (const field of ["aliases", "competitors", "keywords"]) {
                project[field] = project[field].split(/\r?\n/).map(value => value.trim()).filter(Boolean);
            }
            try {
                const saved = await request("/projects", {method: "POST", body: JSON.stringify(project)});
                location.href = `./projects.html?v=${version}&created=${saved.id}`;
            } catch (failure) {
                showError(failure.message); button.disabled = false; button.textContent = "创建项目";
            }
        });
        return;
    }
    let page = 0, keyword = "", loading = false;
    const rows = document.getElementById("project-rows");
    const previous = document.getElementById("previous-page");
    const next = document.getElementById("next-page");
    const detail = document.getElementById("project-detail");
    const labels = {id: "项目 ID", name: "项目／产品名称", websiteUrl: "官网或资料 URL", industry: "行业／品类", coreFeatures: "核心功能", targetAudience: "目标用户群体", aliases: "别名／品牌名", competitors: "主要竞品", targetMarket: "目标市场", targetLanguage: "目标语言", sellingPoints: "核心卖点", pricing: "价格说明", keywords: "目标关键词"};
    function showDetail(project) {
        document.getElementById("detail-name").textContent = project.name;
        const fields = document.getElementById("detail-fields"); fields.replaceChildren();
        for (const [key, label] of Object.entries(labels)) {
            const group = document.createElement("div"), term = document.createElement("dt"), value = document.createElement("dd");
            term.textContent = label; value.textContent = Array.isArray(project[key]) ? project[key].join("\n") || "未填写" : project[key] || "未填写";
            group.append(term, value); fields.append(group);
        }
        detail.hidden = false; detail.scrollIntoView({behavior: "smooth", block: "start"});
    }
    async function loadProjects() {
        loading = true; previous.disabled = true; next.disabled = true; showError(""); detail.hidden = true;
        try {
            const result = await request(`/projects?page=${page}&size=20&keyword=${encodeURIComponent(keyword)}`);
            rows.replaceChildren();
            for (const project of result.items) {
                const row = document.createElement("tr");
                for (const value of [project.id, project.name, project.industry]) {
                    const cell = document.createElement("td"); cell.textContent = value; row.append(cell);
                }
                const sourceCell = document.createElement("td"), source = document.createElement("a");
                source.textContent = "查看资料"; source.href = project.websiteUrl; source.target = "_blank"; source.rel = "noopener noreferrer";
                sourceCell.append(source); row.append(sourceCell);
                const date = document.createElement("td"); date.textContent = new Date(project.createdAt).toLocaleString("zh-CN"); row.append(date);
                const action = document.createElement("td"), button = document.createElement("button");
                button.className = "text-button"; button.type = "button"; button.textContent = "查看信息"; button.addEventListener("click", () => showDetail(project));
                action.append(button); row.append(action); rows.append(row);
            }
            if (!result.items.length) {
                const row = document.createElement("tr"), cell = document.createElement("td"); cell.colSpan = 6; cell.className = "table-message";
                cell.textContent = keyword ? "没有匹配的项目，请调整名称关键词。" : "暂无项目，点击“创建项目”保存第一份基础档案。"; row.append(cell); rows.append(row);
            }
            document.getElementById("project-total").textContent = `共 ${result.total} 个项目`;
            document.getElementById("project-page").textContent = `第 ${page + 1} 页`;
            previous.disabled = page === 0; next.disabled = (page + 1) * 20 >= result.total;
        } catch (failure) { showError(failure.message); document.getElementById("project-total").textContent = "加载失败"; }
        finally { loading = false; }
    }
    document.getElementById("project-search").addEventListener("submit", event => {
        event.preventDefault(); if (loading) return; keyword = document.getElementById("keyword").value.trim(); page = 0; loadProjects();
    });
    previous.addEventListener("click", () => { if (!loading && page > 0) { page--; loadProjects(); } });
    next.addEventListener("click", () => { if (!loading) { page++; loadProjects(); } });
    document.getElementById("close-detail").addEventListener("click", () => { detail.hidden = true; });
    const created = new URLSearchParams(location.search).get("created");
    if (/^\d+$/.test(created || "")) {
        const notice = document.getElementById("project-success"); notice.textContent = `项目创建成功，项目 ID：${created}`; notice.hidden = false;
    }
    loadProjects();
})();
