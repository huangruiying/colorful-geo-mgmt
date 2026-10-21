// 共用请求入口的协议边界测试；使用标准 Response，替换网络，不调用业务服务。
const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

/** 独立加载浏览器脚本，允许每个用例控制一次请求的响应。 */
function createClient(fetch) {
    const context = vm.createContext({window:{}, Headers, fetch});
    vm.runInContext(fs.readFileSync(path.join(__dirname, "../../main/resources/static/admin/shared/http.js"), "utf8"), context);
    return context.window.AdminHttp;
}

test("读取 JSON 并保留调用方请求头、参数与取消信号", async () => {
    const controller = new AbortController();
    const headers = new Headers({Accept:"application/problem+json", "X-Request-Id":"test-request"});
    const client = createClient(async (url, options) => {
        assert.equal(url, "/projects");
        assert.equal(options.headers.get("Accept"), "application/problem+json");
        assert.equal(options.headers.get("X-Request-Id"), "test-request");
        assert.equal(options.headers.has("Content-Type"), false);
        assert.equal(options.signal, controller.signal);
        assert.equal(options.cache, "no-store");
        return Response.json({total:0, items:[]});
    });
    const body = await client.requestJson("/projects", {headers, signal:controller.signal});
    assert.equal(body.total, 0);
    assert.equal(body.items.length, 0);
    assert.equal(headers.has("Content-Type"), false);
});

test("JSON 写入保留方法和原始正文，补充默认请求头", async () => {
    const payload = JSON.stringify({name:"测试项目"});
    const client = createClient(async (url, options) => {
        assert.equal(options.method, "POST");
        assert.equal(options.body, payload);
        assert.equal(options.headers.get("Accept"), "application/json");
        assert.equal(options.headers.get("Content-Type"), "application/json");
        return Response.json({id:12}, {status:201});
    });
    assert.equal((await client.requestJson("/projects", {method:"POST", body:payload})).id, 12);
});

test("支持元组请求头并保留显式 Content-Type", async () => {
    const client = createClient(async (url, options) => {
        assert.equal(options.headers.get("Content-Type"), "application/json; charset=UTF-8");
        return Response.json(false);
    });
    assert.equal(await client.requestJson("/test", {body:"{}", headers:[["Content-Type", "application/json; charset=UTF-8"]]}), false);
});

for (const [status, text] of [[204, null], [200, ""], [200, "  \n"]]) {
    test(`HTTP ${status} 空响应返回 null`, async () => {
        const client = createClient(async () => new Response(text, {status}));
        assert.equal(await client.requestJson("/test"), null);
    });
}

test("HTTP 错误展示 ProblemDetail.detail 并保留状态", async () => {
    const client = createClient(async () => Response.json({detail:"渠道编码已存在"}, {status:409}));
    await assert.rejects(client.requestJson("/llm-channels"), error => {
        assert.equal(error.message, "渠道编码已存在");
        assert.equal(error.status, 409);
        assert.equal("body" in error, false);
        return true;
    });
});

for (const text of ["", "<html>gateway diagnostic</html>", '{"detail":42}', '{"detail":"   "}', "null"]) {
    test(`无可用错误说明时只显示 HTTP 状态：${JSON.stringify(text)}`, async () => {
        const client = createClient(async () => new Response(text, {status:502}));
        await assert.rejects(client.requestJson("/test"), error => error.status === 502 && error.message === "请求失败（HTTP 502）");
    });
}

test("成功状态返回非 JSON 时明确报错，不显示原报文", async () => {
    const client = createClient(async () => new Response("<html>gateway diagnostic</html>"));
    await assert.rejects(client.requestJson("/test"), error => error.status === 200 && error.message === "服务响应格式异常，请稍后重试（HTTP 200）");
});

test("网络失败保留原因，写操作不会自动重试", async () => {
    const cause = new TypeError("network unavailable");
    let calls = 0;
    const client = createClient(async () => { calls++; throw cause; });
    await assert.rejects(client.requestJson("/projects", {method:"POST", body:"{}"}), error => {
        assert.equal(error.message, "网络请求失败，请检查连接后重试");
        assert.equal(error.cause, cause);
        return true;
    });
    assert.equal(calls, 1);
});

test("请求或正文读取被取消时保留原 AbortError", async () => {
    const cause = new DOMException("aborted", "AbortError");
    for (const fetch of [async () => { throw cause; }, async () => ({text:async () => { throw cause; }})]) {
        await assert.rejects(createClient(fetch).requestJson("/test"), error => error === cause);
    }
});
