/** 发布记录列表：展示本地状态和平台结果，删除只作用于本地记录。 */
const publishStatusLabels = window.PublishStatus.labels;
const requestJson = window.AdminHttp.requestJson;
const formatDate = window.AdminFormat.dateTime;
const pageSize = 20;
let currentPage = 0;
/** 下拉框可选的文章，来自 /content-publish/titles，每项带标题、全拼和首字母。 */
let articleOptions = [];
/** 预发布在后台执行，列表里有未完成记录时定时刷新；记录停在“预发布中”待核对时最多刷新 15 分钟。 */
const pendingPublishStatuses = ["INITIALIZED", "PRE_PUBLISHING"];
let refreshTimer = null;
let refreshStartedAt = 0;
/** 当前筛选的文章 ID；为空表示全部文章。 */
let selectedArticleId = null;
/** 下拉列表里当前高亮的选项序号，供键盘上下选择。 */
let activeOptionIndex = -1;
const articleInput = document.querySelector("#article-filter-input");
const articleOptionList = document.querySelector("#article-filter-options");
const recordRows = document.querySelector("#record-rows");
const pageNotice = document.querySelector("#page-notice");
const detailDialog = document.querySelector("#record-dialog");

document.querySelector("#refresh-records").addEventListener("click", () => {
    loadArticleOptions();
    loadContentPublishRecords(currentPage);
});
document.querySelector("#previous-page").addEventListener("click", () => loadContentPublishRecords(currentPage - 1));
document.querySelector("#next-page").addEventListener("click", () => loadContentPublishRecords(currentPage + 1));
document.querySelector("#close-detail").addEventListener("click", () => detailDialog.close());
document.querySelector("#close-detail-bottom").addEventListener("click", () => detailDialog.close());
articleInput.addEventListener("focus", () => { articleInput.select(); renderArticleOptions(""); });
articleInput.addEventListener("input", () => renderArticleOptions(articleInput.value));
articleInput.addEventListener("keydown", handleArticleKeydown);
// 失焦时收起列表，输入框恢复为当前已选文章，未选中的输入不生效
articleInput.addEventListener("blur", () => setTimeout(() => {
    closeArticleOptions();
    articleInput.value = articleOptions.find(option => option.contentOptimizationRecordId === selectedArticleId)?.title || "";
}, 150));
loadArticleOptions();
loadContentPublishRecords(0);

/** 读取有发布记录的文章标题；失败只影响筛选，不影响列表。 */
async function loadArticleOptions() {
    try {
        articleOptions = await requestJson("/content-publish/titles");
        // 已选文章的发布记录被删光后不再出现在选项里，筛选随之回到全部文章，避免输入框与列表不一致
        if (selectedArticleId !== null
            && !articleOptions.some(option => option.contentOptimizationRecordId === selectedArticleId)) {
            selectedArticleId = null;
            articleInput.value = "";
        }
    } catch (error) {
        showNotice(`文章标题加载失败：${error.message}`, true);
    }
}

/** 汉字、全拼或首字母任一包含输入内容即算匹配；不区分大小写，忽略空格。 */
function matchesArticle(option, keyword) {
    return option.title.toLowerCase().replace(/\s+/g, "").includes(keyword)
        || option.pinyin.includes(keyword) || option.pinyinInitials.includes(keyword);
}

/** 按输入内容过滤并展示下拉选项；第一项固定为“全部文章”，最多展示 50 篇。 */
function renderArticleOptions(text) {
    const keyword = text.toLowerCase().replace(/\s+/g, "");
    const matched = articleOptions.filter(option => !keyword || matchesArticle(option, keyword)).slice(0, 50);
    const items = [createArticleOption("全部文章", null)];
    matched.forEach(option => items.push(createArticleOption(option.title, option)));
    if (!matched.length && keyword) {
        const empty = document.createElement("li");
        empty.className = "article-filter-empty";
        empty.textContent = "没有匹配的文章";
        items.push(empty);
    }
    articleOptionList.replaceChildren(...items);
    activeOptionIndex = -1;
    articleOptionList.hidden = false;
    articleInput.setAttribute("aria-expanded", "true");
}

/** 生成一个下拉选项；用 mousedown 选择，避免输入框先失焦把列表收起。 */
function createArticleOption(label, option) {
    const item = document.createElement("li");
    item.setAttribute("role", "option");
    item.textContent = label;
    if (option) {
        const id = document.createElement("small");
        id.textContent = `#${option.contentOptimizationRecordId}`;
        item.append(id);
    }
    item.addEventListener("mousedown", event => {
        event.preventDefault();
        selectArticle(option);
    });
    return item;
}

/** 选中文章（为空表示全部文章），从第一页重新加载列表。 */
function selectArticle(option) {
    selectedArticleId = option ? option.contentOptimizationRecordId : null;
    articleInput.value = option ? option.title : "";
    closeArticleOptions();
    articleInput.blur();
    loadContentPublishRecords(0);
}

/** 键盘上下移动高亮、回车选择、Esc 收起。 */
function handleArticleKeydown(event) {
    if ((event.key === "ArrowDown" || event.key === "ArrowUp") && articleOptionList.hidden) {
        renderArticleOptions(articleInput.value);
    }
    // 在列表重新渲染之后再取选项，避免拿到已被替换的旧节点
    const items = [...articleOptionList.querySelectorAll('[role="option"]')];
    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
        event.preventDefault();
        const step = event.key === "ArrowDown" ? 1 : -1;
        activeOptionIndex = (activeOptionIndex + step + items.length) % items.length;
        items.forEach((item, index) => item.setAttribute("aria-selected", String(index === activeOptionIndex)));
        items[activeOptionIndex]?.scrollIntoView({block: "nearest"});
    } else if (event.key === "Enter" && activeOptionIndex >= 0) {
        event.preventDefault();
        items[activeOptionIndex].dispatchEvent(new MouseEvent("mousedown", {cancelable: true}));
    } else if (event.key === "Escape") {
        closeArticleOptions();
    }
}

function closeArticleOptions() {
    articleOptionList.hidden = true;
    articleInput.setAttribute("aria-expanded", "false");
}

/** 分页读取发布表；当前页删空时退回上一页。 */
async function loadContentPublishRecords(page) {
    try {
        const articleQuery = selectedArticleId === null ? "" : `&contentOptimizationRecordId=${selectedArticleId}`;
        const result = await requestJson(`/content-publish/list?page=${page}&size=${pageSize}${articleQuery}`);
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
        scheduleRefresh(result.items);
    } catch (error) {
        showNotice(`发布列表加载失败：${error.message}`, true);
    }
}

/** 当前页仍有待预发布或预发布中的记录时，3 秒后刷新本页；超过 15 分钟视为待人工核对，不再刷新。 */
function scheduleRefresh(items) {
    clearTimeout(refreshTimer);
    if (!items.some(record => pendingPublishStatuses.includes(record.publishStatus))) {
        refreshStartedAt = 0;
        return;
    }
    if (!refreshStartedAt) refreshStartedAt = Date.now();
    if (Date.now() - refreshStartedAt > 15 * 60 * 1000) return;
    refreshTimer = setTimeout(() => loadContentPublishRecords(currentPage), 3000);
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
    title.textContent = record.platformDisplayName;
    const titleText = document.createElement("small");
    titleText.textContent = record.publicationTitle || "未命名";
    title.append(titleText);

    const status = document.createElement("td");
    status.textContent = publishStatusLabels[record.publishStatus] || record.publishStatus || "未知";
    const result = document.createElement("td");
    result.className = "publication-result";
    if (record.remoteContentId) appendText(result, `平台 ID：${record.remoteContentId}`);
    // 使用平台草稿入口链接。
    appendDraftEntry(result, record);
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
    if (!window.confirm(`确定删除本地发布记录 #${record.id}（${record.platformDisplayName}）吗？\n${warning}此操作不会删除平台上的内容。`)) return;
    try {
        await requestJson(`/content-publish/detail/${record.id}`, {method: "DELETE"});
        await loadArticleOptions();
        await loadContentPublishRecords(currentPage);
        showNotice(`本地发布记录 #${record.id} 已删除，平台内容未受影响。`, false);
    } catch (error) {
        showNotice(`删除失败：${error.message}`, true);
    }
}

/** 详情以 Markdown 展示最终稿快照，只改变视图，不触发平台接口或修改已发布内容。 */
function openRecordDetail(record) {
    document.querySelector("#detail-title").textContent = `发布记录 #${record.id}`;
    MarkdownView.render(document.querySelector("#detail-content"), record.publicationContent || "—");
    const meta = document.querySelector("#detail-meta");
    meta.replaceChildren();
    [
        `优化记录 ID：${record.contentOptimizationRecordId}`,
        `平台：${record.platformDisplayName}`,
        `状态：${publishStatusLabels[record.publishStatus] || record.publishStatus}`,
        `最终发布标题：${record.publicationTitle || "—"}`,
        `平台内容 ID：${record.remoteContentId || "—"}`,
        `失败/说明原因：${record.failureReason || "—"}`,
        `创建时间：${formatDate(record.createdAt)}`,
        `更新时间：${formatDate(record.updatedAt)}`
    ].forEach(value => appendText(meta, value));
    appendDraftEntry(meta, record);
    appendWebLink(meta, record.publishedUrl, "打开文章");
    detailDialog.showModal();
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
    cell.textContent = selectedArticleId === null ? "暂无发布记录。" : "这篇文章暂无发布记录。";
    row.append(cell);
    return row;
}

function showNotice(message, error) {
    pageNotice.textContent = message;
    pageNotice.className = `notice ${error ? "notice-error" : "notice-loading"}`;
    pageNotice.hidden = false;
}

