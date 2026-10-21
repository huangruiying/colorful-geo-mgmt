"use strict";
/** 管理页面共用的 JSON 请求入口；写请求附带跨站令牌，未登录时跳转登录页；不记录报文、不重试，也不决定页面交互。 */
(() => {
    /** 登录页地址；管理页面都在同一目录下。 */
    const LOGIN_PAGE = "./login.html";
    /** 只有写请求需要跨站令牌，查询不附加。 */
    const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

    /**
     * 发起一次 JSON 请求；空响应返回 null，失败抛出可展示的错误，HTTP 错误保留 status。
     * @param {string} path 接口地址
     * @param {RequestInit} options fetch 参数；body 沿用调用方序列化的 JSON，signal 可取消等待
     * @returns {Promise<*>} 接口 JSON 数据或 null
     */
    async function requestJson(path, options = {}) {
        // 合并而非替换调用方的请求头；没有正文的查询不附加 Content-Type。
        const headers = new Headers(options.headers);
        if (!headers.has("Accept")) {
            headers.set("Accept", "application/json");
        }
        if (options.body != null && !headers.has("Content-Type")) {
            headers.set("Content-Type", "application/json");
        }
        if (!SAFE_METHODS.has((options.method || "GET").toUpperCase())) {
            headers.set("X-XSRF-TOKEN", csrfToken());
        }
        let response, text;
        try {
            response = await fetch(path, {...options, headers, cache:"no-store"});
            text = await response.text();
        } catch (cause) {
            // 取消由请求的页面识别；网络失败不说明服务端未执行，不能自动重发。
            if (cause.name === "AbortError") {
                throw cause;
            }
            throw new Error("网络请求失败，请检查连接后重试", {cause});
        }
        return parseJsonResponse(response, text);
    }

    /** 解析成功正文及 ProblemDetail；非 JSON 错误响应只展示状态，不将网关报文带入页面。 */
    function parseJsonResponse(response, text) {
        let body = null;
        if (text.trim()) {
            try {
                body = JSON.parse(text);
            } catch {
                const message = response.ok ? "服务响应格式异常，请稍后重试" : "请求失败";
                throw createResponseError(message + "（HTTP " + response.status + "）", response.status);
            }
        }
        // 登录过期或未登录：整页回到登录页，登录后由服务端回到原页面
        if (response.status === 401) {
            window.location.assign(LOGIN_PAGE + "?expired");
        }
        if (!response.ok) {
            const detail = typeof body?.detail === "string" ? body.detail.trim() : "";
            throw createResponseError(detail || "请求失败（HTTP " + response.status + "）", response.status);
        }
        // DELETE 等接口可以返回 204；普通成功响应为空时也不强行解析 JSON。
        return body;
    }

    /** 仅保留状态供调用方判断，不把请求、密钥或完整响应挂到错误对象上。 */
    function createResponseError(message, status) {
        return Object.assign(new Error(message), {status});
    }

    /**
     * 读取服务端写入 Cookie 的跨站令牌；登录表单与退出也用它，不在页面各自解析 Cookie。
     * @returns {string} 当前令牌，Cookie 尚未写入时为空字符串，服务端会拒绝并提示刷新页面
     */
    function csrfToken() {
        const cookie = document.cookie.split("; ").find(item => item.startsWith("XSRF-TOKEN="));
        return cookie ? decodeURIComponent(cookie.slice("XSRF-TOKEN=".length)) : "";
    }

    window.AdminHttp = Object.freeze({requestJson, csrfToken});
})();
