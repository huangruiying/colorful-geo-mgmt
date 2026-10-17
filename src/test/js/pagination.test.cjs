const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

/** 加载实际分页脚本，接口替身只提供确定的 PageResponse。 */
function createPagination(requestJson) {
    const context = vm.createContext({URL, window:{location:{href:"http://localhost/admin/history-report.html"}, AdminHttp:{requestJson}}});
    vm.runInContext(fs.readFileSync(path.join(__dirname, "../../main/resources/static/admin/shared/pagination.js"), "utf8"), context);
    return context.window.AdminPagination;
}

test("超过 100 条的任务逐页全部读取，保留原筛选与 signal", async () => {
    const seen = [], controller = new AbortController();
    const pagination = createPagination(async (path, options) => {
        const url = new URL(path), page = Number(url.searchParams.get("page"));
        seen.push(page);
        assert.equal(url.searchParams.get("status"), "SUCCEEDED");
        assert.equal(url.searchParams.get("size"), "100");
        assert.equal(options.signal, controller.signal);
        return {items:Array.from({length:page === 0 ? 100 : 1}, (_, index) => page * 100 + index), total:101};
    });
    const items = await pagination.listAll("/diagnosis-tasks?status=SUCCEEDED&page=8", {signal:controller.signal});
    assert.equal(items.length, 101); assert.equal(items[100], 100);
    assert.deepEqual(seen, [0, 1]);
});

test("空页结束，不因总数变化无限读取", async () => {
    let calls = 0;
    const pagination = createPagination(async () => { calls++; return {items:[], total:101}; });
    assert.equal((await pagination.listAll("/diagnosis-tasks")).length, 0);
    assert.equal(calls, 1);
});

test("后续页失败不返回部分选项", async () => {
    let calls = 0;
    const error = new Error("第二页读取失败");
    const pagination = createPagination(async () => {
        if (++calls === 2) throw error;
        return {items:[1], total:2};
    });
    await assert.rejects(pagination.listAll("/diagnosis-tasks"), value => value === error);
});

test("窗口取消后不再请求下一页", async () => {
    let calls = 0;
    const controller = new AbortController();
    const pagination = createPagination(async () => {
        calls++; controller.abort();
        return {items:[1], total:2};
    });
    await assert.rejects(pagination.listAll("/diagnosis-tasks", {signal:controller.signal}), error => error.name === "AbortError");
    assert.equal(calls, 1);
});
