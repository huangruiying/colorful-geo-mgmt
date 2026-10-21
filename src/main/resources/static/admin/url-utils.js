/**
 * 管理后台网页链接与平台草稿入口；调用方先加载此脚本，不拼接原始 HTML。
 */

/** 只允许网页协议进入可点击链接，避免平台或模型返回可执行的 URL。 */
function safeWebUrl(value) {
    try {
        const url = new URL(value);
        return ["http:", "https:"].includes(url.protocol) ? url.href : null;
    } catch {
        return null;
    }
}

/** 返回平台草稿入口；公众号进入文章列表，由用户自行选择，不定位具体草稿。 */
function draftEntry(record) {
    if (record.platformType === "WECHAT_OFFICIAL_ACCOUNT" && record.remoteContentId) {
        return {url: "https://mp.weixin.qq.com/cgi-bin/appmsg?begin=0&count=10&type=77&action=list_card&lang=zh_CN", label: "打开草稿", title: "进入公众号文章列表，自行选择草稿；如平台要求，请先登录"};
    }
    return {url: withDraftId(record.draftUrl, record.remoteContentId), label: "打开草稿", title: "打开平台草稿"};
}

/** 将平台草稿入口展示为普通链接，不创建额外的查看流程。 */
function appendDraftEntry(container, record) {
    const entry = draftEntry(record);
    const link = appendWebLink(container, entry.url, entry.label);
    if (link) link.title = entry.title || "打开平台草稿";
}

/** 添加仅允许 HTTP(S) 的独立窗口链接；无效地址返回 null，调用方决定是否显示纯文本。 */
function appendWebLink(container, url, label) {
    const href = safeWebUrl(url);
    if (!href) return null;
    const link = document.createElement("a");
    link.href = href;
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.textContent = label;
    container.append(link);
    return link;
}

/** 为「打开草稿」链接补上平台草稿 ID。
 *  - 链接本身已含该 ID（有草稿深链的平台，如百家号 `article_id=`、头条）→ 原样返回，不污染真实深链；
 *  - 链接不含 ID（东方财富）→ 以 hash 参数 `id` 携带，生成 `#/?id=<draft_id>` 真深链，可直接打开该草稿。
 *  注意：东方财富深链参数名是 `id`（形如 `.../index.html#/?id=<draft_id>`），不是 `draftId`，
 *  用错 key 平台不识别、打开的是空白编辑器而非具体草稿。 */
function withDraftId(url, draftId) {
    if (!url || !draftId) return url;
    return String(url).includes(String(draftId)) ? url : appendHashParam(url, "id", draftId);
}

/** 把平台草稿 ID 作为 hash 参数附加到链接上，保证点击「打开草稿」时 ID 随 URL 携带。 */
function appendHashParam(url, key, value) {
    try {
        const parsed = new URL(url);
        if (parsed.hash) {
            const sep = parsed.hash.includes("?") ? "&" : "?";
            parsed.hash = parsed.hash + sep + encodeURIComponent(key) + "=" + encodeURIComponent(value);
        } else {
            parsed.searchParams.set(key, value);
        }
        return parsed.href;
    } catch {
        return url;
    }
}
