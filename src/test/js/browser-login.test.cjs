// 登录模式切换回归：使用虚拟页面与接口，验证取消扫码及轮询的生命周期。
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

function createLoginPage(platforms = []) {
    const elements = new Map();
    const calls = [];
    const timers = new Map();
    let nextTimer = 0;
    const element = id => {
        if (!elements.has(id)) elements.set(id, {
            hidden: false, open: true, value: id === 'status-filter' ? 'ALL' : '', dataset: {},
            classList: { toggle() {}, add() {}, remove() {} }, children: [],
            listeners: {}, addEventListener(type, handler) { this.listeners[type] = handler; },
            dispatch(type) { this.listeners[type]?.(); },
            querySelectorAll: () => [], append(...children) { this.children.push(...children); },
            replaceChildren(...children) { this.children = children; },
            removeAttribute(name) { delete this[name]; }, close() { this.open = false; },
            showModal() { this.open = true; }
        });
        return elements.get(id);
    };
    const context = vm.createContext({
        document: {
            getElementById: element,
            createElement: tag => ({ tag, children: [], dataset: {}, hidden: false,
                append(...children) { this.children.push(...children); }, addEventListener() {} }),
            querySelector: () => element('native-scan-method'),
            querySelectorAll: () => []
        },
        window: { addEventListener() {} }, AbortController, URLSearchParams,
        setInterval(callback) { timers.set(++nextTimer, callback); return nextTimer; },
        clearInterval(id) { timers.delete(id); },
        fetch: async (url, options) => {
            calls.push({ url, options });
            return { ok: true, text: async () => url.endsWith('/login-statuses') ? JSON.stringify(platforms)
                : url.endsWith('/cancel') ? '' : '{"status":"LOGIN_PENDING"}' };
        }
    });
    vm.runInContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/admin/browser-login.js'), 'utf8'), context);
    vm.runInContext("activePlatform = 'ZHIHU'; platformNames.ZHIHU = '知乎'; startPolling();", context);
    return { context, calls, timers, element, run: code => vm.runInContext(code, context) };
}

for (const mode of ['sms', 'password']) {
    test(`切到 ${mode} 取消旧扫码且停止查询`, async () => {
        const page = createLoginPage();
        page.element('login-screenshot').src = 'old-qr';
        await page.run(`switchLoginMode('${mode}')`);
        assert.equal(page.timers.size, 0);
        assert.equal(page.element('login-screenshot').hidden, true);
        assert.equal(page.element('scan-preview').hidden, true);
        assert.equal(page.element('login-screenshot').src, undefined);
        assert.equal(page.calls.filter(call => call.url.endsWith('/login/cancel')).length, 1);
        assert.equal(page.calls.at(-1).options.method, 'POST');
        page.run("renderProgress({status:'LOGIN_PENDING', screenshotBase64:'late-qr'})");
        assert.equal(page.element('login-screenshot').src, undefined);
    });
}

test('切换时终止尚未返回的扫码查询，旧响应不能恢复轮询', async () => {
    const page = createLoginPage();
    let querySignal;
    const originalFetch = page.context.fetch;
    page.context.fetch = (url, options) => {
        if (!url.endsWith('/scan/login/result')) return originalFetch(url, options);
        querySignal = options.signal;
        return new Promise((resolve, reject) => options.signal.addEventListener('abort', () =>
            reject(Object.assign(new Error('取消查询'), { name: 'AbortError' }))));
    };
    const query = page.run('refreshLogin()');
    await page.run("switchLoginMode('sms')");
    await query;
    assert.equal(querySignal.aborted, true);
    assert.equal(page.timers.size, 0);
    assert.equal(page.element('login-feedback').textContent, '请输入手机号并获取验证码。');
});

test('发码不查询登录结果，提交短信码后才开始查询', async () => {
    const page = createLoginPage();
    await page.run("switchLoginMode('sms')");
    await page.run("submitLoginAction('sms-code', {phone:'test'})");
    assert.equal(page.timers.size, 0);
    await page.run("submitLoginAction('sms', {code:'test'})");
    assert.equal(page.timers.size, 1);
});

test('提交密码后查询新登录结果，切回扫码重新打开扫码会话', async () => {
    const page = createLoginPage();
    await page.run('loadPlatforms()');
    page.run("platformScanMethods.ZHIHU=['PLATFORM_APP']; platformLoginMethods.ZHIHU=['qr','sms','password']; platformNames.ZHIHU='知乎'");
    await page.run("switchLoginMode('password')");
    await page.run("submitLoginAction('password', {account:'test', password:'test'})");
    assert.equal(page.timers.size, 1);
    await page.run("switchLoginMode('qr')");
    assert.equal(page.element('scan-preview').hidden, false);
    assert.equal(page.element('login-screenshot').hidden, true);
    assert(page.calls.some(call => call.url.includes('scanMethod=PLATFORM_APP')));
    assert.equal(page.timers.size, 1);
});

test('第三方扫码仍待授权时提示手机确认，不误显示为知乎已登录', () => {
    const page = createLoginPage();
    page.run("activeScanMethod = 'WECHAT'; renderProgress({status:'LOGIN_PENDING', interactionStage:'WAITING_FOR_THIRD_PARTY'})");
    assert.match(page.element('login-feedback').textContent, /微信扫码和授权/);
    assert.equal(page.element('login-task').open, true);
});

test('第三方弹窗已关闭且知乎账号未就绪时提示重新扫码', () => {
    const page = createLoginPage();
    page.run("activeScanMethod = 'QQ'; renderProgress({status:'LOGIN_PENDING', interactionStage:'INTERACTION_WINDOW_CLOSED'})");
    assert.match(page.element('login-feedback').textContent, /未检测到知乎登录/);
    assert.equal(page.element('login-task').open, true);
});

test('刷新状态按钮只检测已登录平台并刷新列表', async () => {
    const page = createLoginPage();
    const originalFetch = page.context.fetch;
    page.context.fetch = (url, options) => {
        if (!url.endsWith('/logged-in/check')) return originalFetch(url, options);
        page.calls.push({ url, options });
        return Promise.resolve({ ok: true, text: async () => '[]' });
    };

    await page.run('checkLoggedInPlatforms()');

    const check = page.calls.find(call => call.url.endsWith('/logged-in/check'));
    assert.equal(check.options.method, 'POST');
    assert.match(page.element('notice').textContent, /没有需要检测的已登录平台/);
    assert.equal(page.element('check-logged-in-platforms').disabled, false);
});

test('概览只统计已接入平台，并把失效登录态计入未登录', () => {
    const page = createLoginPage();
    page.run("renderLoginSummary([" +
        "{supported:true,status:'LOGGED_IN'}," +
        "{supported:true,status:'EXPIRED'}," +
        "{supported:true,status:'UNKNOWN'}," +
        "{supported:false,status:'NOT_SUPPORTED'}])");

    assert.equal(page.element('total-count').textContent, 3);
    assert.equal(page.element('logged-in-count').textContent, 1);
    assert.equal(page.element('not-logged-in-count').textContent, 1);
    assert.equal(page.element('unknown-count').textContent, 1);
});

test('单个状态筛选区分未登录与已失效', async () => {
    const page = createLoginPage([
        {platformName: '知乎', supported: true, status: 'LOGGED_IN'},
        {platformName: '微博', supported: true, status: 'NOT_LOGGED_IN'},
        {platformName: '抖音', supported: true, status: 'EXPIRED'},
        {platformName: '小红书', supported: true, status: 'UNKNOWN'},
        {platformName: '豆瓣', supported: false, status: 'NOT_SUPPORTED'}
    ]);
    await page.run('loadPlatforms()');

    const statusFilter = page.element('status-filter');
    statusFilter.value = 'NOT_LOGGED_IN';
    statusFilter.dispatch('change');
    const visibleNames = () => page.element('platform-rows').children
        .filter(row => !row.hidden).map(row => row.children[0].textContent);
    assert.deepEqual(visibleNames(), ['微博']);
    assert.equal(page.element('total-count').textContent, 4);

    statusFilter.value = 'EXPIRED';
    statusFilter.dispatch('change');
    assert.deepEqual(visibleNames(), ['抖音']);

    statusFilter.value = 'NOT_SUPPORTED';
    statusFilter.dispatch('change');
    assert.deepEqual(visibleNames(), ['豆瓣']);
});

test('扫码图片加载前显示占位，加载成功后才显示图片', async () => {
    const page = createLoginPage();

    await page.run("startLogin('ZHIHU')");
    assert.equal(page.element('scan-preview').hidden, false);
    assert.equal(page.element('scan-placeholder').hidden, false);
    assert.equal(page.element('login-screenshot').hidden, true);

    page.run("renderProgress({status:'LOGIN_PENDING', screenshotBase64:'qr-image'})");
    page.element('login-screenshot').dispatch('load');
    assert.equal(page.element('scan-placeholder').hidden, true);
    assert.equal(page.element('login-screenshot').hidden, false);
});

test('扫码图片加载失败时显示重试提示', async () => {
    const page = createLoginPage();
    await page.run("startLogin('ZHIHU')");

    page.run("renderProgress({status:'LOGIN_PENDING', screenshotBase64:'broken-image'})");
    page.element('login-screenshot').dispatch('error');

    assert.equal(page.element('login-screenshot').hidden, true);
    assert.equal(page.element('scan-placeholder').hidden, false);
    assert.match(page.element('scan-placeholder-text').textContent, /刷新扫码图片/);
});

test('登录页打开失败时在弹窗显示原因并允许重试', async () => {
    const page = createLoginPage();
    const originalFetch = page.context.fetch;
    let failOnce = true;
    page.context.fetch = (url, options) => {
        if (url.includes('/XIAOHONGSHU/login?') && failOnce) {
            failOnce = false;
            return Promise.resolve({ ok: false, status: 400,
                json: async () => ({ message: '小红书加载二维码失败，请检查网络后重试' }) });
        }
        return originalFetch(url, options);
    };

    await page.run("startLogin('XIAOHONGSHU')");
    assert.equal(page.element('login-task').open, true);
    assert.match(page.element('login-feedback').textContent, /小红书加载二维码失败/);
    assert.equal(page.element('refresh-login').hidden, false);
    assert.equal(page.element('refresh-login').textContent, '重试打开登录页');

    await page.run('refreshScanImage()');
    assert.equal(page.element('login-task').open, true);
    assert.equal(page.element('refresh-login').textContent, '刷新扫码图片');
});
