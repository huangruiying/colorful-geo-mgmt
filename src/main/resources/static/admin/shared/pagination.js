"use strict";
/** 下拉选项的完整分页读取；列表页面的页码与业务筛选仍由各页面维护。 */
(() => {
    /** 逐页读取现有 PageResponse，保留原查询条件；中途失败不返回看似完整的部分选项。 */
    async function listAll(path, {signal} = {}) {
        const url = new URL(path, window.location.href);
        const items = [];
        url.searchParams.set("size", "100");
        for (let page = 0; ; page++) {
            signal?.throwIfAborted();
            url.searchParams.set("page", page);
            const result = await window.AdminHttp.requestJson(url.href, {signal});
            signal?.throwIfAborted();
            items.push(...result.items);
            if (!result.items.length || items.length >= result.total) {
                return items;
            }
        }
    }
    window.AdminPagination = Object.freeze({listAll});
})();
