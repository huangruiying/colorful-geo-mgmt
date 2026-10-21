// 使用可控 Promise 和计时器验证读取生命周期，不等待真实时间或业务接口。
const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

/** 每次加载独立的公共脚本与可手动触发的计时器。 */
function createRuntime() {
    const timers = new Map();
    let nextId = 0;
    const context = vm.createContext({window:{}, AbortController,
        setTimeout(callback, delay) { timers.set(++nextId, {callback, delay}); return nextId; },
        clearTimeout(id) { timers.delete(id); }
    });
    vm.runInContext(fs.readFileSync(path.join(__dirname, "../../main/resources/static/admin/shared/async.js"), "utf8"), context);
    return {...context.window.AdminAsync, timers};
}

/** 让测试决定请求完成顺序，覆盖网络忽略取消后仍返回的情况。 */
function deferred() {
    let resolve, reject;
    const promise = new Promise((success, failure) => { resolve = success; reject = failure; });
    return {promise, resolve, reject};
}

test("切换读取会 abort 旧请求，旧响应不能覆盖当前值", async () => {
    const scope = createRuntime().createRequestScope(), old = deferred();
    let signal, value;
    const first = scope.run(async ticket => {
        signal = ticket.signal;
        const result = await old.promise;
        if (ticket.isCurrent()) value = result;
    });
    await scope.run(async ticket => { if (ticket.isCurrent()) value = "新回答"; });
    assert.equal(signal.aborted, true);
    old.resolve("旧回答");
    await first;
    assert.equal(value, "新回答");
});

test("关闭后旧请求的错误被忽略，当前请求错误仍向上报告", async () => {
    const scope = createRuntime().createRequestScope(), old = deferred();
    const first = scope.run(() => old.promise);
    scope.cancel(); old.reject(new Error("过期网络错误"));
    await first;
    const current = new Error("当前网络错误");
    await assert.rejects(scope.run(async () => { throw current; }), error => error === current);
});

test("轮询等待本轮完成后再安排下一轮，终态不再安排", async () => {
    const runtime = createRuntime(), pending = deferred();
    let calls = 0;
    const poller = runtime.createPoller(async () => ++calls === 1 ? pending.promise : false, () => assert.fail("不应失败"));
    const first = poller.refresh();
    assert.equal(runtime.timers.size, 0);
    pending.resolve(true); await first;
    const [id, timer] = [...runtime.timers][0];
    assert.equal(timer.delay, 3000);
    runtime.timers.delete(id); await timer.callback();
    assert.equal(calls, 2);
    assert.equal(runtime.timers.size, 0);
});

test("手动刷新替代未完成读取，旧轮询不会重新启动", async () => {
    const runtime = createRuntime(), old = deferred();
    let calls = 0, oldSignal;
    const poller = runtime.createPoller(async ticket => {
        if (++calls === 1) { oldSignal = ticket.signal; return old.promise; }
        return false;
    }, () => assert.fail("不应失败"));
    const first = poller.refresh();
    await poller.refresh();
    old.resolve(true); await first;
    assert.equal(oldSignal.aborted, true);
    assert.equal(runtime.timers.size, 0);
});

test("停止会取消请求和计时器，即使旧响应返回进行中也不恢复", async () => {
    const runtime = createRuntime(), pending = deferred();
    let signal;
    const poller = runtime.createPoller(ticket => { signal = ticket.signal; return pending.promise; }, () => assert.fail("不应失败"));
    const first = poller.refresh(); poller.stop();
    assert.equal(signal.aborted, true);
    pending.resolve(true); await first;
    assert.equal(runtime.timers.size, 0);
    await poller.refresh();
    assert.equal(runtime.timers.size, 1);
    poller.stop(); assert.equal(runtime.timers.size, 0);
});

for (const status of [undefined, 429, 503, 404]) {
    test(`读请求错误 ${status ?? "网络失败"} 提示并按类型决定是否重试`, async () => {
        const runtime = createRuntime(), error = Object.assign(new Error("测试错误"), {status});
        const shown = [];
        const poller = runtime.createPoller(async () => { throw error; }, value => shown.push(value));
        await poller.refresh();
        assert.equal(shown[0], error);
        assert.equal(runtime.timers.size, status === 404 ? 0 : 1);
        if (status !== 404) assert.equal([...runtime.timers.values()][0].delay, 5000);
        poller.stop();
    });
}

test("旧轮询失败不会提示到新对象，也不会安排重试", async () => {
    const runtime = createRuntime(), old = deferred();
    let calls = 0;
    const poller = runtime.createPoller(() => ++calls === 1 ? old.promise : false, () => assert.fail("旧错误不应提示"));
    const first = poller.refresh(); await poller.refresh();
    old.reject(new Error("旧对象失败")); await first;
    assert.equal(runtime.timers.size, 0);
});
