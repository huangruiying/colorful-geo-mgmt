/**
 * 管理后台共用的 URL 辅助函数。
 *
 * <p>此前 safeWebUrl / appendHashParam 只写在 contents.js 里，publications.js 直接引用导致
 * 「appendHashParam is not defined」。收敛到本文件（两个页面都在各自脚本前以 defer 引入），
 * 作为唯一实现来源，避免再次漂移。</p>
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

/** 公众号按发布记录打开服务端只读窗口，其他平台保留现有草稿链接。 */
function draftEntry(record) {
    if (record.platformType === "WECHAT_OFFICIAL_ACCOUNT" && record.remoteContentId) {
        return {previewRecordId: record.id, label: "打开草稿"};
    }
    // 小红书纯 SPA 无单篇深链，「打开草稿」只能带到创作者中心草稿箱入口，由用户按标题查找。
    if (record.platformType === "XIAOHONGSHU") {
        return {url: withDraftId(record.draftUrl, record.remoteContentId), label: "打开草稿", title: "进入创作者中心草稿箱，按标题查找文章"};
    }
    return {url: withDraftId(record.draftUrl, record.remoteContentId), label: "打开草稿", title: "打开平台草稿"};
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
