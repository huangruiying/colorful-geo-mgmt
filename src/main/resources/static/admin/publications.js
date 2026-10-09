/** 发布记录列表：展示本地状态和平台结果，删除只作用于本地记录。 */
const publishStatusLabels = {
    INITIALIZED: "待预发布", PRE_PUBLISHING: "预发布中／待核对", PRE_PUBLISHED: "预发布成功",
    PUBLISHING: "实际发布中", PUBLISHED: "实际发布成功",
    PRE_PUBLISH_FAILED: "预发布失败", PUBLISH_FAILED: "实际发布失败", CANCELLED: "已取消"
};
const pageSize = 20;
let currentPage = 0;
const recordRows = document.querySelector("#record-rows");
const pageNotice = document.querySelector("#page-notice");
const detailDialog = document.querySelector("#record-dialog");

document.querySelector("#refresh-records").addEventListener("click", () => loadContentPublishRecords(currentPage));
document.querySelector("#previous-page").addEventListener("click", () => loadContentPublishRecords(currentPage - 1));
document.querySelector("#next-page").addEventListener("click", () => loadContentPublishRecords(currentPage + 1));
document.querySelector("#close-detail").addEventListener("click", () => detailDialog.close());
document.querySelector("#close-detail-bottom").addEventListener("click", () => detailDialog.close());
loadContentPublishRecords(0);

/** 接口失败时读取 ProblemDetail；删除成功的 204 响应没有正文。 */
async function requestJson(path, options = {}) {
    const response = await fetch(path, {headers: {Accept: "application/json"}, cache: "no-store", ...options});
    const body = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) throw new Error(body?.detail || `接口返回 HTTP ${response.status}`);
    return body;
}

/** 分页读取发布表；当前页删空时退回上一页。 */
async function loadContentPublishRecords(page) {
    try {
        const result = await requestJson(`/content-publish/list?page=${page}&size=${pageSize}`);
        if (page > 0 && result.items.length === 0 && result.total > 0) {
            await loadContentPublishRecords(page - 1);
            return;
        }
        currentPage = page;
        recordRows.replaceChildren(...(result.items.length ? result.items.map(createRecordRow) : [createEmptyRow()]));
        document.querySelector("#record-total").textContent = `共 ${result.total} 条`;
        document.querySelector("#page-label").textContent = `第 ${page + 1} 页`;
        document.querySelector("#previous-page").disabled = page === 0;
        document.querySelector("#next-page").disabled = (page + 1) * pageSize >= result.total;
        pageNotice.hidden = true;
    } catch (error) {
        showNotice(`发布列表加载失败：${error.message}`, true);
    }
}

/** 将一条发布记录的状态、来源和平台结果放到独立表行。 */
function createRecordRow(record) {
    const row = document.createElement("tr");
    const identity = document.createElement("td");
    identity.textContent = `发布 #${record.id}`;
    const source = document.createElement("small");
    source.textContent = `优化记录 #${record.contentOptimizationRecordId}`;
    identity.append(source);

    const title = document.createElement("td");
    title.className = "publication-title";
    title.textContent = record.platformType || "未知平台";
    const titleText = document.createElement("small");
    titleText.textContent = record.publicationTitle || "未命名";
    title.append(titleText);

    const status = document.createElement("td");
    status.textContent = publishStatusLabels[record.publishStatus] || record.publishStatus || "未知";
    const result = document.createElement("td");
    result.className = "publication-result";
    if (record.remoteContentId) appendText(result, `平台 ID：${record.remoteContentId}`);
    // 有深链的平台（URL 已含 ID）原样保留；无深链的（东方财富）以 hash 参数携带草稿 ID。
    appendWebLink(result, withDraftId(record.draftUrl, record.remoteContentId), "打开草稿");
    appendWebLink(result, record.publishedUrl, "打开文章");
    if (record.failureReason) {
        const reason = document.createElement("div");
        reason.className = "publication-failure";
        reason.textContent = record.failureReason;
        result.append(reason);
    }
    if (!result.hasChildNodes()) result.textContent = "—";

    const time = document.createElement("td");
    time.textContent = formatDate(record.createdAt);
    const updated = document.createElement("small");
    updated.textContent = `更新：${formatDate(record.updatedAt)}`;
    time.append(updated);

    const action = document.createElement("td");
    const actions = document.createElement("div");
    actions.className = "row-actions";
    actions.append(createActionButton("详情", () => openRecordDetail(record)));
    const deleting = record.publishStatus === "PRE_PUBLISHING" || record.publishStatus === "PUBLISHING";
    const deleteButton = createActionButton("删除", () => deleteContentPublishRecord(record));
    deleteButton.classList.add("delete-button");
    deleteButton.disabled = deleting;
    deleteButton.title = deleting ? "执行中的记录不可删除" : "只删除本地记录";
    actions.append(deleteButton);
    action.append(actions);
    row.append(identity, title, status, result, time, action);
    return row;
}

/** 二次确认删除范围；服务端仍会校验记录的实时状态。 */
async function deleteContentPublishRecord(record) {
    const warning = record.publishStatus === "PUBLISHED" || record.publishStatus === "PRE_PUBLISHED"
        ? "平台上的草稿或文章仍会保留；删除后可能再次提交同一内容。" : "";
    if (!window.confirm(`确定删除本地发布记录 #${record.id}（${record.platformType}）吗？\n${warning}此操作不会删除平台上的内容。`)) return;
    try {
        await requestJson(`/content-publish/detail/${record.id}`, {method: "DELETE"});
        await loadContentPublishRecords(currentPage);
        showNotice(`本地发布记录 #${record.id} 已删除，平台内容未受影响。`, false);
    } catch (error) {
        showNotice(`删除失败：${error.message}`, true);
    }
}

/** 详情展示最终稿快照，不再触发平台接口。 */
function openRecordDetail(record) {
    document.querySelector("#detail-title").textContent = `发布记录 #${record.id}`;
    document.querySelector("#detail-content").textContent = record.publicationContent || "—";
    const meta = document.querySelector("#detail-meta");
    meta.replaceChildren();
    [
        `优化记录 ID：${record.contentOptimizationRecordId}`,
        `平台：${record.platformType}`,
        `状态：${publishStatusLabels[record.publishStatus] || record.publishStatus}`,
        `最终发布标题：${record.publicationTitle || "—"}`,
        `平台内容 ID：${record.remoteContentId || "—"}`,
        `失败或待核对原因：${record.failureReason || "—"}`,
        `创建时间：${formatDate(record.createdAt)}`,
        `更新时间：${formatDate(record.updatedAt)}`
    ].forEach(value => appendText(meta, value));
    appendWebLink(meta, withDraftId(record.draftUrl, record.remoteContentId), "打开草稿");
    appendWebLink(meta, record.publishedUrl, "打开文章");
    detailDialog.showModal();
}

/** URL 只允许 HTTP(S)（safeWebUrl 定义在 url-utils.js），平台返回值不直接拼入 HTML。 */
function appendWebLink(container, url, label) {
    const href = safeWebUrl(url);
    if (!href) return;
    const link = document.createElement("a");
    link.href = href;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.textContent = label;
    container.append(link);
}

function appendText(container, value) {
    const line = document.createElement("div");
    line.textContent = value;
    container.append(line);
}

function createActionButton(label, onClick) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "text-button";
    button.textContent = label;
    button.addEventListener("click", onClick);
    return button;
}

function createEmptyRow() {
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    cell.colSpan = 6;
    cell.className = "table-message";
    cell.textContent = "暂无发布记录。";
    row.append(cell);
    return row;
}

function showNotice(message, error) {
    pageNotice.textContent = message;
    pageNotice.className = `notice ${error ? "notice-error" : "notice-loading"}`;
    pageNotice.hidden = false;
}

function formatDate(value) {
    return value ? new Date(value).toLocaleString("zh-CN", {hour12: false}) : "—";
}
