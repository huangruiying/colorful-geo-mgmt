/** 内容工作台：优化稿入库后，在预发布弹窗确认最终稿并创建逐平台草稿。 */
const publishStatusLabels = {
    INITIALIZED: "待预发布", PRE_PUBLISHING: "预发布中／待核对", PRE_PUBLISHED: "预发布成功",
    PUBLISHING: "实际发布中", PUBLISHED: "实际发布成功",
    PRE_PUBLISH_FAILED: "预发布失败", PUBLISH_FAILED: "实际发布失败", CANCELLED: "已取消"
};
const pageSize = 20;
let currentPage = 0;
let currentDetail = null;
let selectedRecordId = null;
let checkedRecordId = null;
let platformOptionsPromise = null;
const platformDisplayNames = new Map();

const recordRows = document.querySelector("#record-rows");
const pageNotice = document.querySelector("#page-notice");
const optimizeDialog = document.querySelector("#optimize-dialog");
const detailDialog = document.querySelector("#detail-dialog");
const platformDialog = document.querySelector("#platform-dialog");

document.querySelector("#open-optimize").addEventListener("click", () => optimizeDialog.showModal());
document.querySelector("#optimize-form").addEventListener("submit", submitOptimization);
document.querySelector("#submit-prepublish").addEventListener("click", submitPrePublish);
document.querySelector("#prepublish-selected").addEventListener("click", () => openPlatformSelection(checkedRecordId));
document.querySelector("#select-all-platforms").addEventListener("click", () => togglePlatformSelection(true));
document.querySelector("#clear-platforms").addEventListener("click", () => togglePlatformSelection(false));
document.querySelector("#previous-page").addEventListener("click", () => loadContentRecords(currentPage - 1));
document.querySelector("#next-page").addEventListener("click", () => loadContentRecords(currentPage + 1));
document.querySelectorAll("[data-close]").forEach(button => button.addEventListener("click", () => {
    document.getElementById(button.dataset.close).close();
}));
loadContentRecords(0);

/** 只接收 JSON 结果；接口失败优先显示可读的业务说明。 */
async function requestJson(path, options = {}) {
    const response = await fetch(path, {
        ...options,
        headers: {Accept: "application/json", ...(options.body ? {"Content-Type": "application/json"} : {})},
        cache: "no-store"
    });
    const body = await response.json().catch(() => null);
    if (!response.ok) {
        throw new Error(body?.detail || `接口返回 HTTP ${response.status}`);
    }
    return body;
}

/** 分页加载摘要，正文只在用户查看详情时读取。 */
async function loadContentRecords(page) {
    try {
        const result = await requestJson(`/content-optimize/list?page=${page}&size=${pageSize}`);
        currentPage = page;
        checkedRecordId = null;
        document.querySelector("#prepublish-selected").disabled = true;
        renderContentRows(result.items || []);
        document.querySelector("#record-total").textContent = `共 ${result.total} 篇`;
        document.querySelector("#page-label").textContent = `第 ${page + 1} 页`;
        document.querySelector("#previous-page").disabled = page === 0;
        document.querySelector("#next-page").disabled = (page + 1) * pageSize >= result.total;
        hideMessage(pageNotice);
    } catch (error) {
        showMessage(pageNotice, `内容列表加载失败：${error.message}`, "error");
        if (recordRows.querySelector(".table-message")) {
            showEmptyRows("暂时无法读取内容记录，请刷新页面重试。");
        }
    }
}

/** 列表只展示发布概览，单篇的草稿链接留在详情里。 */
function renderContentRows(records) {
    if (records.length === 0) {
        showEmptyRows("暂无内容记录，请点击“新建优化”开始。");
        return;
    }
    recordRows.replaceChildren(...records.map(record => {
        const row = document.createElement("tr");
        const selectCell = document.createElement("td");
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.className = "record-checkbox";
        checkbox.setAttribute("aria-label", `选择内容：${record.title}`);
        checkbox.addEventListener("change", () => {
            document.querySelectorAll(".record-checkbox").forEach(input => {
                if (input !== checkbox) input.checked = false;
            });
            checkedRecordId = checkbox.checked ? record.id : null;
            document.querySelector("#prepublish-selected").disabled = checkedRecordId === null;
        });
        selectCell.append(checkbox);
        const titleCell = document.createElement("td");
        titleCell.className = "record-title";
        titleCell.textContent = record.title;
        const id = document.createElement("small");
        id.className = "record-id";
        id.textContent = `记录 #${record.id}`;
        titleCell.append(id);

        const statusCell = document.createElement("td");
        statusCell.textContent = record.publishRecordCount ? "查看各平台状态" : "待预发布";
        const draftCell = document.createElement("td");
        draftCell.textContent = `${record.draftCount} 草稿 · ${record.processingCount} 待核对 · ${record.failedCount} 失败`;
        const dateCell = document.createElement("td");
        dateCell.textContent = formatDate(record.createdAt);
        const actionCell = document.createElement("td");
        const actions = document.createElement("div");
        actions.className = "row-actions";
        actions.append(createActionButton("查看详情", () => openContentDetail(record.id)));
        actions.append(createActionButton("预发布", () => openPlatformSelection(record.id)));
        actionCell.append(actions);
        row.append(selectCell, titleCell, statusCell, draftCell, dateCell, actionCell);
        return row;
    }));
}

/** 优化成功后返回已入库记录，再刷新列表；失败时不伪造新记录。 */
async function submitOptimization(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const button = document.querySelector("#submit-optimize");
    const errorNode = document.querySelector("#optimize-error");
    button.disabled = true;
    button.textContent = "正在优化并保存…";
    hideMessage(errorNode);
    try {
        const fields = new FormData(form);
        const body = {
            title: String(fields.get("title") || "").trim(),
            content: String(fields.get("content") || ""),
            optimizationPreference: String(fields.get("optimizationPreference") || ""),
            strategies: [], references: []
        };
        await requestJson("/content-optimize/optimize", {method: "POST", body: JSON.stringify(body)});
        form.reset();
        optimizeDialog.close();
        await loadContentRecords(0);
    } catch (error) {
        showMessage(errorNode, `优化或保存失败：${error.message}`, "error");
    } finally {
        button.disabled = false;
        button.textContent = "优化并保存";
    }
}

/** 读取原文、优化稿及各平台发布结果；最终稿仅在预发布弹窗中确认。 */
async function openContentDetail(recordId) {
    try {
        const [detail] = await Promise.all([
            requestJson(`/content-optimize/detail/${recordId}`), loadPlatformOptions()
        ]);
        currentDetail = detail;
        const record = currentDetail.record;
        document.querySelector("#detail-title").textContent = record.title;
        document.querySelector("#original-content").textContent = record.originalContent;
        document.querySelector("#optimized-content").textContent = record.optimizedContent;
        renderWarnings(record.warnings || []);
        renderCitations(record.citations || []);
        renderPublishRecords(currentDetail.publishRecords || []);
        detailDialog.showModal();
    } catch (error) {
        showMessage(pageNotice, `内容详情读取失败：${error.message}`, "error");
    }
}

/** 按优化记录 ID 找到各平台发布记录；平台状态以数据库记录为准。 */
async function openPlatformSelection(recordId) {
    const errorNode = document.querySelector("#platform-error");
    hideMessage(errorNode);
    try {
        const [detail, platforms] = await Promise.all([
            requestJson(`/content-optimize/detail/${recordId}`), loadPlatformOptions()
        ]);
        selectedRecordId = recordId;
        document.querySelector("#platform-dialog-title").textContent = `${detail.record.title} · 选择一个或多个平台创建草稿`;
        document.querySelector("#publication-title").value = detail.record.title;
        document.querySelector("#publication-content").value = detail.record.optimizedContent;
        const publishRecords = new Map((detail.publishRecords || [])
            .filter(record => record.contentOptimizationRecordId === detail.record.id)
            .map(record => [record.platformType, record]));
        const options = document.querySelector("#platform-options");
        options.replaceChildren(...platforms.map(platform =>
            createPlatformOption(platform, publishRecords.get(platform.platformType))));
        platformDialog.showModal();
    } catch (error) {
        showMessage(pageNotice, `平台选择加载失败：${error.message}`, "error");
    }
}

/** 用户确认最终稿和平台后才提交；明确预发布失败的平台允许重新创建草稿。 */
async function submitPrePublish() {
    const selected = [...document.querySelectorAll("#platform-options input:checked")].map(input => input.value);
    const publicationTitle = document.querySelector("#publication-title").value.trim();
    const publicationContent = document.querySelector("#publication-content").value;
    const errorNode = document.querySelector("#platform-error");
    if (!publicationTitle || !publicationContent.trim()) {
        showMessage(errorNode, "请确认最终发布标题和正文。", "error");
        return;
    }
    if (selected.length === 0) {
        showMessage(errorNode, "请至少选择一个待预发布或预发布失败的平台。", "error");
        return;
    }
    const button = document.querySelector("#submit-prepublish");
    button.disabled = true;
    button.textContent = "正在创建草稿…";
    hideMessage(errorNode);
    try {
        await requestJson(`/content-publish/${selectedRecordId}/pre-publish`, {
            method: "POST", body: JSON.stringify({publicationTitle, publicationContent, platformTypes: selected})
        });
        platformDialog.close();
        await loadContentRecords(currentPage);
        await openContentDetail(selectedRecordId);
    } catch (error) {
        showMessage(errorNode, `预发布未完成：${error.message}。请先核对平台草稿箱和发布记录；只有明确失败的平台可重试。`, "error");
    } finally {
        button.disabled = false;
        button.textContent = "确认并创建草稿";
    }
}

/** 仅成功状态置灰；明确失败可重试，进行中及待核对状态不可重复提交。 */
function createPlatformOption(platform, publishRecord) {
    const label = document.createElement("label");
    label.className = "platform-option";
    const succeeded = ["PRE_PUBLISHED", "PUBLISHED"].includes(publishRecord?.publishStatus);
    const retryable = publishRecord?.publishStatus === "PRE_PUBLISH_FAILED"
        && !publishRecord.remoteContentId && !publishRecord.draftUrl && !publishRecord.publishedUrl;
    if (succeeded) label.classList.add("platform-option-success");
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.value = platform.platformType;
    checkbox.disabled = Boolean(publishRecord) && !retryable;
    const name = document.createElement("span");
    const status = publishRecord
        ? publishStatusLabels[publishRecord.publishStatus] || "状态未知，待核对" : "";
    name.textContent = `${platform.displayName}${status ? ` · ${status}` : ""}${retryable ? " · 可重试" : ""}`;
    label.append(checkbox, name);
    return label;
}

/** 平台展示名只读取一次；请求失败后允许再次加载。 */
async function loadPlatformOptions() {
    if (!platformOptionsPromise) {
        platformOptionsPromise = requestJson("/publication/platforms").then(platforms => {
            platforms.forEach(platform => platformDisplayNames.set(platform.platformType, platform.displayName));
            return platforms;
        }).catch(error => {
            platformOptionsPromise = null;
            throw error;
        });
    }
    return platformOptionsPromise;
}

function togglePlatformSelection(checked) {
    document.querySelectorAll("#platform-options input:not(:disabled)").forEach(input => { input.checked = checked; });
}

function renderPublishRecords(records) {
    const container = document.querySelector("#publish-record-list");
    container.replaceChildren(...records.map(record => {
        const item = document.createElement("div");
        item.className = "publish-record-item";
        const name = document.createElement("strong");
        name.textContent = platformDisplayNames.get(record.platformType) || record.platformType;
        const status = document.createElement("span");
        status.textContent = publishStatusLabels[record.publishStatus] || record.publishStatus;
        item.append(name, status);
        if (record.remoteContentId) {
            const id = document.createElement("span");
            id.textContent = `平台 ID：${record.remoteContentId}`;
            item.append(id);
        }
        const draftUrl = safeWebUrl(record.draftUrl);
        if (draftUrl) {
            const link = document.createElement("a");
            // withDraftId：有草稿深链的平台原样保留；东方财富（hash 路由、无深链）以 hash
            // 参数携带平台 ID，保证点击「打开草稿」时 ID 随 URL 传递，便于在「草稿箱」按 ID 核对。
            link.href = withDraftId(draftUrl, record.remoteContentId);
            link.target = "_blank";
            link.rel = "noopener noreferrer";
            link.textContent = "打开草稿";
            item.append(link);
        }
        if (record.failureReason) {
            const reason = document.createElement("span");
            reason.textContent = record.failureReason;
            item.append(reason);
        }
        return item;
    }));
}

function renderWarnings(warnings) {
    const node = document.querySelector("#optimization-warnings");
    node.hidden = warnings.length === 0;
    node.textContent = warnings.join("\n");
}

function renderCitations(citations) {
    const node = document.querySelector("#optimization-citations");
    node.hidden = citations.length === 0;
    node.replaceChildren(...citations.map(citation => {
        const url = safeWebUrl(citation.url);
        const element = document.createElement(url ? "a" : "span");
        element.textContent = citation.title || citation.url || "未命名引用";
        if (url) {
            element.href = url;
            element.target = "_blank";
            element.rel = "noopener noreferrer";
        }
        return element;
    }));
}

function createActionButton(label, action) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "text-button";
    button.textContent = label;
    button.addEventListener("click", action);
    return button;
}

function showEmptyRows(message) {
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    cell.className = "table-message";
    cell.colSpan = 6;
    cell.textContent = message;
    row.append(cell);
    recordRows.replaceChildren(row);
}

function formatDate(value) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString("zh-CN", {hour12: false});
}

function showMessage(node, message, type) {
    node.textContent = message;
    node.className = node === pageNotice ? `notice notice-${type}` : "form-error";
    node.hidden = false;
}

function hideMessage(node) {
    node.hidden = true;
}
