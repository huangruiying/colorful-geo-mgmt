/** 两个管理页面共用的草稿查看窗口；公众号读取服务端真实草稿，其他平台仍用原链接。 */
let draftPreviewDialog;
let draftPreviewRequest;
let draftPreviewRecordId;

/** 公众号使用只读窗口，其他平台保留可直接跳转的草稿链接。 */
function appendDraftEntry(container, record) {
    const entry = draftEntry(record);
    if (entry.previewRecordId) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "text-button";
        button.textContent = entry.label;
        button.addEventListener("click", () => openDraftPreview(entry.previewRecordId));
        container.append(button);
        return;
    }
    const href = safeWebUrl(entry.url);
    if (!href) return;
    const link = document.createElement("a");
    link.href = href;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.textContent = entry.label;
    link.title = entry.title || "打开平台草稿";
    container.append(link);
}

/** 按需创建一个只读窗口；关闭即取消前端等待，后端读取结束会自动释放浏览器。 */
function createDraftPreviewDialog() {
    const dialog = document.createElement("dialog");
    dialog.id = "draft-preview-dialog";
    dialog.className = "modal modal-wide";
    // 固定结构不拼接平台内容；标题、正文和错误信息统一使用 textContent。
    dialog.innerHTML = `<div class="modal-body">
        <div class="modal-heading"><div><h2>公众号草稿</h2><p>平台当前草稿的只读视图，不支持编辑或发表。</p></div>
        <button class="close-button" type="button" data-close-draft aria-label="关闭草稿">×</button></div>
        <p data-draft-status role="status" aria-live="polite"></p>
        <h3 data-draft-title hidden></h3>
        <img data-draft-image alt="公众号当前草稿编辑页截图" hidden>
        <section data-draft-body hidden><h3>平台当前正文</h3><div class="article-preview" data-draft-content></div></section>
        <div class="modal-actions"><button class="text-button" type="button" data-refresh-draft>刷新草稿</button>
        <button class="text-button" type="button" data-close-draft>关闭</button></div></div>`;
    dialog.querySelector("[data-draft-image]").style.width = "100%";
    dialog.querySelector("[data-draft-content]").style.whiteSpace = "pre-wrap";
    dialog.querySelectorAll("[data-close-draft]").forEach(button => button.addEventListener("click", () => dialog.close()));
    dialog.querySelector("[data-refresh-draft]").addEventListener("click", () => openDraftPreview(draftPreviewRecordId));
    dialog.addEventListener("close", () => {
        draftPreviewRequest?.abort();
        draftPreviewRequest = null;
        clearDraftPreview(dialog);
    });
    document.body.append(dialog);
    return dialog;
}

/** 换稿、刷新、关闭时清理前一篇的截图和正文，避免旧结果被误认为当前草稿。 */
function clearDraftPreview(dialog) {
    const image = dialog.querySelector("[data-draft-image]");
    image.hidden = true;
    image.removeAttribute("src");
    dialog.querySelector("[data-draft-title]").hidden = true;
    dialog.querySelector("[data-draft-title]").textContent = "";
    dialog.querySelector("[data-draft-body]").hidden = true;
    dialog.querySelector("[data-draft-content]").textContent = "";
}

/** 请求平台实际草稿；失败显示原因，不回退到本地确认稿冒充平台内容。 */
async function openDraftPreview(recordId) {
    draftPreviewDialog ||= createDraftPreviewDialog();
    const dialog = draftPreviewDialog;
    draftPreviewRecordId = recordId;
    draftPreviewRequest?.abort();
    const request = new AbortController();
    draftPreviewRequest = request;
    clearDraftPreview(dialog);
    const status = dialog.querySelector("[data-draft-status]");
    const refresh = dialog.querySelector("[data-refresh-draft]");
    status.textContent = "正在打开对应的公众号草稿，请稍候…";
    refresh.disabled = true;
    if (!dialog.open) dialog.showModal();
    try {
        const response = await fetch(`/content-publish/detail/${encodeURIComponent(recordId)}/draft-preview`, {
            method: "POST", headers: {Accept: "application/json"}, cache: "no-store", signal: request.signal
        });
        const draft = await response.json().catch(() => null);
        if (!response.ok) throw new Error(draft?.detail || `打开草稿失败（HTTP ${response.status}）`);
        if (request.signal.aborted || !dialog.open) return;
        dialog.querySelector("[data-draft-title]").textContent = draft.title;
        dialog.querySelector("[data-draft-title]").hidden = false;
        const image = dialog.querySelector("[data-draft-image]");
        image.src = `data:image/png;base64,${draft.screenshotBase64}`;
        image.hidden = false;
        dialog.querySelector("[data-draft-content]").textContent = draft.content;
        dialog.querySelector("[data-draft-body]").hidden = false;
        status.textContent = "已打开对应草稿；以下为平台当前内容，仅供查看。";
    } catch (error) {
        if (!request.signal.aborted) status.textContent = `打开草稿失败：${error.message}`;
    } finally {
        if (draftPreviewRequest === request) refresh.disabled = false;
    }
}
