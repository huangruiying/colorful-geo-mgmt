// 独立 Playwright 登录页：只展示平台状态和扫码截图，不读取或展示数据库 Cookie。
const baseUrl = '/admin/browser-login/platforms';
const platformRows = document.getElementById('platform-rows');
const notice = document.getElementById('notice');
const loginTask = document.getElementById('login-task');
const scanPreview = document.getElementById('scan-preview');
const scanPlaceholder = document.getElementById('scan-placeholder');
const scanPlaceholderText = document.getElementById('scan-placeholder-text');
const loginScreenshot = document.getElementById('login-screenshot');
const loginFeedback = document.getElementById('login-feedback');
const platformAgreementLinks = {
    BAIJIAHAO: ['https://baijiahao.baidu.com/docs/#/markdownsingle/BaiJiaHaoFuWuXieYi', '《百家号用户协议》'],
    SOHU: ['https://mp.sohu.com/baike#/dashboard?id=435536343', '《搜狐号用户服务协议》'],
    YUQUE: ['https://www.yuque.com/terms', '《语雀服务协议》'],
    EASTMONEY: ['https://mp.eastmoney.com/', '在东方财富登录页查看《隐私政策》和《服务协议》'],
    OSCHINA: ['https://www.oschina.net/home/login', '在开源中国登录页查看《服务条例》和《隐私声明》'],
    TOUTIAO: ['https://mp.toutiao.com/profile_v3_public/public/protocol/agreement', '《头条号用户协议》'],
    NETEASE: ['https://hc.reg.163.com/iTerm/doc.html?id=542', '《网易服务条款》']
};
let activePlatform = null;
let platformNames = {};
let platformLoginMethods = {};
let platformScanMethods = {};
let activeScanMethod = 'PLATFORM_APP';
let loginGeneration = 0;
let pollTimer = null;
let polling = false;
let loginBusy = false;
let loginPageFailed = false;
let cancelPending = false;
let loginMode = 'qr';
let pollAbortController = null;

async function request(path, options = {}) {
    const response = await fetch(baseUrl + path, {
        ...options,
        cache: 'no-store',
        headers: options.body ? { 'Content-Type': 'application/json' } : undefined
    });
    if (!response.ok) {
        const body = await response.json().catch(() => ({}));
        throw new Error(body.message || body.detail || `请求失败（${response.status}）`);
    }
    const body = await response.text();
    return body ? JSON.parse(body) : null;
}

function showNotice(message) {
    notice.textContent = message;
    notice.hidden = !message;
}

function statusText(status) {
    return {
        NOT_SUPPORTED: '未接入', NOT_LOGGED_IN: '未登录', LOGIN_PENDING: '等待登录',
        LOGGED_IN: '已登录', EXPIRED: '已失效', UNKNOWN: '无法确认', NOT_STARTED: '未启动'
    }[status] || status;
}

// 接入状态只依据服务端已保存的账号核验结果，不能把打开登录页当成验证完成。
function integrationText(platform) {
    if (!platform.supported) return '待接入';
    return platform.validatedLogin ? '已验证' : '待验证';
}

// 只统计已开放浏览器登录的平台；未接入项不参与登录状态分组。
function renderLoginSummary(platforms) {
    const connected = platforms.filter(platform => platform.supported);
    const loggedIn = connected.filter(platform => platform.status === 'LOGGED_IN').length;
    const notLoggedIn = connected.filter(platform =>
        platform.status === 'NOT_LOGGED_IN' || platform.status === 'EXPIRED').length;
    document.getElementById('total-count').textContent = connected.length;
    document.getElementById('logged-in-count').textContent = loggedIn;
    document.getElementById('not-logged-in-count').textContent = notLoggedIn;
    document.getElementById('unknown-count').textContent = connected.length - loggedIn - notLoggedIn;
}

async function loadPlatforms() {
    try {
        const platforms = await request('/login-statuses');
        platformNames = Object.fromEntries(platforms.map(platform => [platform.platformType, platform.platformName]));
        platformLoginMethods = Object.fromEntries(platforms.map(platform =>
            [platform.platformType, platform.loginMethods || []]));
        platformScanMethods = Object.fromEntries(platforms.map(platform =>
            [platform.platformType, platform.scanMethods || []]));
        renderLoginSummary(platforms);
        platformRows.replaceChildren();
        for (const platform of platforms) {
            const row = document.createElement('tr');
            // 列表筛选按实际登录状态区分未登录和已失效；未接入单独展示。
            row.dataset.filterStatus = !platform.supported ? 'NOT_SUPPORTED'
                : ['LOGGED_IN', 'NOT_LOGGED_IN', 'EXPIRED'].includes(platform.status)
                    ? platform.status : 'UNKNOWN';
            for (const [index, value] of [platform.platformName, integrationText(platform), statusText(platform.status),
                platform.accountName || '—', platform.lastCheckedAt || '—'].entries()) {
                const cell = document.createElement('td');
                if (index === 1) {
                    const badge = document.createElement('span');
                    const integrationStyle = !platform.supported ? 'status-unknown'
                        : platform.validatedLogin ? 'status-logged-in' : 'integration-pending';
                    badge.className = `status-badge ${integrationStyle}`;
                    badge.textContent = value;
                    cell.append(badge);
                } else if (index === 2) {
                    const badge = document.createElement('span');
                    const statusStyle = {
                        LOGGED_IN: 'status-logged-in', NOT_LOGGED_IN: 'status-not-logged-in',
                        EXPIRED: 'status-not-logged-in'
                    }[platform.status] || 'status-unknown';
                    badge.className = `status-badge ${statusStyle}`;
                    badge.textContent = value;
                    cell.append(badge);
                } else cell.textContent = value;
                row.append(cell);
            }
            const action = document.createElement('td');
            if (platform.supported) {
                const loginButton = document.createElement('button');
                loginButton.textContent = platform.status === 'LOGGED_IN' ? '重新登录' : '登录';
                loginButton.addEventListener('click', () => startLogin(platform.platformType));
                const checkButton = document.createElement('button');
                checkButton.textContent = '检测';
                checkButton.disabled = platform.status === 'NOT_LOGGED_IN';
                checkButton.addEventListener('click', () => checkLogin(platform.platformType));
                action.append(loginButton, checkButton);
            } else {
                action.textContent = '待接入';
            }
            row.append(action);
            platformRows.append(row);
        }
        const emptyRow = document.createElement('tr');
        const emptyCell = document.createElement('td');
        emptyCell.colSpan = 6;
        emptyCell.textContent = '暂无符合该状态的平台';
        emptyRow.append(emptyCell);
        platformRows.append(emptyRow);
        filterPlatformRows();
        showNotice('');
    } catch (error) {
        showNotice(error.message);
    }
}

function filterPlatformRows() {
    const status = document.getElementById('status-filter').value;
    const rows = [...platformRows.children];
    let visibleCount = 0;
    for (const row of rows.slice(0, -1)) {
        row.hidden = status !== 'ALL' && row.dataset.filterStatus !== status;
        if (!row.hidden) visibleCount++;
    }
    rows.at(-1).hidden = visibleCount > 0;
}

function stopPolling() {
    if (pollTimer) clearInterval(pollTimer);
    pollTimer = null;
    pollAbortController?.abort();
}

function startPolling() {
    stopPolling();
    if (activePlatform) pollTimer = setInterval(refreshLogin, 3000);
}

function selectLoginMode(mode) {
    loginMode = mode;
    for (const button of document.querySelectorAll('[data-login-mode]')) {
        button.classList.toggle('selected', button.dataset.loginMode === mode);
    }
    for (const panel of document.querySelectorAll('[data-login-panel]')) {
        panel.hidden = panel.dataset.loginPanel !== mode;
    }
    // 非扫码模式隐藏整个图片区并清空图片；后续轮询也不再填入二维码。
    scanPreview.hidden = mode !== 'qr';
    if (mode !== 'qr') document.getElementById('douyin-verification').hidden = true;
    if (scanPreview.hidden) {
        loginScreenshot.hidden = true;
        loginScreenshot.removeAttribute('src');
    }
    const retryButton = document.getElementById('refresh-login');
    retryButton.hidden = mode !== 'qr';
    retryButton.textContent = '刷新扫码图片';
}

// 切换登录方式时先释放旧会话，防止旧二维码仍能完成本次登录。
async function switchLoginMode(mode) {
    if (loginBusy || mode === loginMode || !activePlatform) return;
    if (loginPageFailed) return startLogin(activePlatform, platformScanMethods[activePlatform]?.[0] || 'PLATFORM_APP', mode);
    if (mode === 'qr') {
        const scanMethod = platformScanMethods[activePlatform]?.[0];
        if (scanMethod) return startLogin(activePlatform, scanMethod, 'qr');
        return;
    }
    // 表单平台从第三方扫码返回时要重开自己的表单浏览器；短信和密码之间仍共用当前页面。
    if (activePlatform !== 'ZHIHU') {
        if (loginMode === 'qr') return startLogin(activePlatform, 'PLATFORM_APP', mode);
        stopPolling();
        selectLoginMode(mode);
        loginFeedback.textContent = mode === 'sms' ? '请输入手机号并获取验证码。' : '请输入账号和密码。';
        return;
    }
    ++loginGeneration;
    stopPolling();
    selectLoginMode(mode);
    setLoginBusy(true);
    loginFeedback.textContent = '正在取消上一次登录…';
    try {
        await request(`/${activePlatform}/login/cancel`, { method: 'POST' });
        loginFeedback.textContent = mode === 'sms' ? '请输入手机号并获取验证码。' : '请输入账号和密码。';
    } catch (error) {
        loginFeedback.textContent = error.message;
        activePlatform = null;
        loginTask.close();
        showNotice(`取消旧登录失败，请重新打开登录窗口：${error.message}`);
    } finally {
        setLoginBusy(false);
    }
}

// 登录操作串行进行，避免取消旧会话时同时创建新会话。
function setLoginBusy(busy) {
    loginBusy = busy || cancelPending;
    for (const control of loginTask.querySelectorAll('button, input')) {
        if (control.id !== 'cancel-login' && control.id !== 'login-close') control.disabled = loginBusy;
    }
}

function selectScanMethod(scanMethod) {
    activeScanMethod = scanMethod;
    for (const button of document.querySelectorAll('[data-scan-method]')) {
        button.classList.toggle('selected', button.dataset.scanMethod === scanMethod);
    }
    const nativeScanner = {
        WECHAT_OFFICIAL_ACCOUNT: '微信', ZHIHU: '知乎 App', JUEJIN: '稀土掘金 App',
        WEIBO: '微博 App', BILIBILI: '哔哩哔哩客户端', TOUTIAO: '今日头条 App',
        XIAOHONGSHU: '小红书 App', DOUYIN: '抖音 App', DAYU: 'UC 浏览器'
    }[activePlatform] || `${platformNames[activePlatform]} App`;
    const scanner = { PLATFORM_APP: nativeScanner, WECHAT: '微信', QQ: 'QQ' }[scanMethod];
    const platformName = platformNames[activePlatform];
    document.getElementById('scan-instruction').textContent =
        `使用${scanner}扫描下方二维码，登录${platformName}账号。`;
    loginScreenshot.alt = `${scanner}登录${platformName}二维码`;
}

function renderProgress(progress) {
    loginFeedback.classList.toggle('login-feedback-error', false);
    const platformName = platformNames[activePlatform] || activePlatform;
    const interactionHint = {
        WAITING_FOR_THIRD_PARTY: `请在手机上完成${activeScanMethod === 'WECHAT' ? '微信' : 'QQ'}扫码和授权；${platformName}登录尚未完成。`,
        RETURNED_TO_PLATFORM: `授权页面已返回${platformName}，正在核验账号。`,
        INTERACTION_WINDOW_CLOSED: `授权窗口已关闭，但未检测到${platformName}登录；请重新扫码。`,
        DOUYIN_IDENTITY_VERIFICATION: '抖音要求再次验证身份，请选择下方短信验证方式。',
        DOUYIN_SMS_CODE: '请填写抖音发送的身份验证码；提交后继续等待账号核验。',
        DOUYIN_SEND_SMS: '请按抖音页面提示从手机发送验证短信，完成后等待账号核验。'
    }[progress.interactionStage];
    const douyinStage = activePlatform === 'DOUYIN' && loginMode === 'qr'
        && progress.interactionStage?.startsWith('DOUYIN_');
    document.getElementById('douyin-verification').hidden = !douyinStage;
    document.getElementById('douyin-verification-methods').hidden =
        progress.interactionStage !== 'DOUYIN_IDENTITY_VERIFICATION';
    document.getElementById('douyin-verification-code-form').hidden =
        progress.interactionStage !== 'DOUYIN_SMS_CODE';
    loginFeedback.textContent = interactionHint ||
        `${platformName}：${statusText(progress.status)}${progress.accountName ? ` · ${progress.accountName}` : ''}`;
    if (progress.screenshotBase64 && loginMode === 'qr') {
        const screenshotSource = `data:image/png;base64,${progress.screenshotBase64}`;
        if (loginScreenshot.src !== screenshotSource) {
            scanPlaceholder.hidden = false;
            scanPlaceholderText.textContent = '正在加载扫码图片…';
            loginScreenshot.hidden = true;
            loginScreenshot.src = screenshotSource;
        }
    }
    if (progress.status === 'LOGGED_IN' || progress.status === 'EXPIRED' || progress.status === 'NOT_STARTED') {
        stopPolling();
        loginTask.close();
        scanPreview.hidden = true;
        loginScreenshot.removeAttribute('src');
        activePlatform = null;
        loadPlatforms().then(() => showNotice(`${platformName}：${statusText(progress.status)}${progress.accountName ? ` · ${progress.accountName}` : ''}`));
    }
}

// 二维码失效后可能显示授权页面截图，按原尺寸缩放方便查看后续提示。
loginScreenshot.addEventListener('load', () => {
    if (loginMode !== 'qr' || !activePlatform || scanPreview.hidden) return;
    loginScreenshot.classList.toggle('full-page', loginScreenshot.naturalWidth > 500);
    scanPlaceholder.hidden = true;
    loginScreenshot.hidden = false;
});
loginScreenshot.addEventListener('error', () => {
    if (loginMode !== 'qr' || !activePlatform || scanPreview.hidden) return;
    loginScreenshot.hidden = true;
    scanPlaceholder.hidden = false;
    scanPlaceholderText.textContent = '扫码图片加载失败，请点击“刷新扫码图片”重试。';
});

async function startLogin(platform, scanMethod = 'PLATFORM_APP', requestedMode) {
    if (loginBusy) return;
    if ((scanMethod === 'WECHAT' || scanMethod === 'QQ') && platformAgreementLinks[platform]
        && !document.getElementById('agree-to-platform-terms').checked) {
        loginFeedback.textContent = `请先阅读并同意${platformNames[platform]}用户协议。`;
        return;
    }
    setLoginBusy(true);
    const generation = ++loginGeneration;
    try {
        stopPolling();
        showNotice('');
        activePlatform = platform;
        document.getElementById('login-title').textContent = `${platformNames[platform] || platform}登录`;
        document.getElementById('login-description').textContent = platform === 'ZHIHU'
            ? '在此完成扫码或输入登录信息；确认登录成功后自动保存会话和账号状态。'
            : '按此平台支持的方式登录；确认账号身份后自动保存会话。';
        const methods = platformLoginMethods[platform] || ['qr'];
        document.getElementById('platform-agreement').hidden = !platformAgreementLinks[platform];
        if (!loginTask.open) document.getElementById('agree-to-platform-terms').checked = false;
        if (platformAgreementLinks[platform]) {
            const agreementLink = document.getElementById('platform-agreement-link');
            agreementLink.href = platformAgreementLinks[platform][0];
            agreementLink.textContent = platformAgreementLinks[platform][1];
        }
        document.getElementById('login-modes').hidden = methods.length < 2;
        for (const button of document.querySelectorAll('[data-login-mode]')) {
            button.hidden = !methods.includes(button.dataset.loginMode);
        }
        const scanMethods = platformScanMethods[platform] || [];
        document.getElementById('scan-methods').hidden = scanMethods.length < 2;
        for (const button of document.querySelectorAll('[data-scan-method]')) {
            button.hidden = !scanMethods.includes(button.dataset.scanMethod);
        }
        document.querySelector('[data-scan-method="PLATFORM_APP"]').textContent =
            platform === 'ZHIHU' ? '知乎 App' : '平台 App';
        const mode = requestedMode || methods[0];
        selectLoginMode(mode);
        if (methods.includes('qr')) selectScanMethod(scanMethod);
        scanPreview.hidden = mode !== 'qr';
        scanPlaceholder.hidden = false;
        scanPlaceholderText.textContent = '正在加载扫码图片…';
        loginScreenshot.hidden = true;
        loginScreenshot.removeAttribute('src');
        loginFeedback.textContent = mode === 'qr' ? '正在加载扫码图片…' : '正在打开平台登录页…';
        loginFeedback.classList.toggle('login-feedback-error', false);
        if (!loginTask.open) loginTask.showModal();
        const params = new URLSearchParams({
            agreedToPlatformTerms: document.getElementById('agree-to-platform-terms').checked
        });
        if (mode === 'qr') params.set('scanMethod', scanMethod);
        const scanQuery = `?${params}`;
        const progress = await request(`/${platform}/login${scanQuery}`, { method: 'POST' });
        if (generation !== loginGeneration) return;
        loginPageFailed = false;
        renderProgress(progress);
        if (mode === 'qr') startPolling();
    } catch (error) {
        if (generation !== loginGeneration) return;
        stopPolling();
        loginPageFailed = true;
        loginFeedback.textContent = `${platformNames[platform] || platform}登录页打开失败：${error.message}`;
        loginFeedback.classList.toggle('login-feedback-error', true);
        scanPreview.hidden = true;
        loginScreenshot.removeAttribute('src');
        const retryButton = document.getElementById('refresh-login');
        retryButton.hidden = false;
        retryButton.textContent = '重试打开登录页';
        showNotice(`${platformNames[platform] || platform}登录失败：${error.message}`);
    } finally {
        setLoginBusy(false);
    }
}

async function submitLoginAction(path, payload) {
    if (!activePlatform || loginBusy) return;
    if (platformAgreementLinks[activePlatform] && !document.getElementById('agree-to-platform-terms').checked) {
        loginFeedback.textContent = `请先阅读并同意${platformNames[activePlatform]}用户协议。`;
        return;
    }
    if (path === 'sms-code' || path === 'password') {
        payload.agreedToPlatformTerms = document.getElementById('agree-to-platform-terms').checked;
    }
    setLoginBusy(true);
    ++loginGeneration;
    stopPolling();
    loginFeedback.textContent = `正在操作${platformNames[activePlatform] || activePlatform}登录页…`;
    loginFeedback.classList.toggle('login-feedback-error', false);
    try {
        renderProgress(await request(`/${activePlatform}/login/${path}`, {
            method: 'POST', body: JSON.stringify(payload)
        }));
        // 发码本身不代表开始登录；提交验证码或密码后才查询本次登录结果。
        if (path !== 'sms-code') startPolling();
    } catch (error) {
        loginFeedback.textContent = error.message;
        loginFeedback.classList.toggle('login-feedback-error', true);
    } finally {
        setLoginBusy(false);
    }
}

async function refreshLogin() {
    if (!activePlatform || polling) return;
    const platform = activePlatform;
    const generation = loginGeneration;
    polling = true;
    pollAbortController = new AbortController();
    try {
        const progress = await request(`/${platform}/scan/login/result`, { signal: pollAbortController.signal });
        if (activePlatform === platform && generation === loginGeneration) renderProgress(progress);
    } catch (error) {
        if (error.name === 'AbortError' || generation !== loginGeneration) return;
        stopPolling();
        loginFeedback.textContent = error.message;
        loginFeedback.classList.toggle('login-feedback-error', true);
    } finally {
        polling = false;
        pollAbortController = null;
    }
}

async function refreshScanImage() {
    if (activePlatform) await startLogin(activePlatform, activeScanMethod, loginMode);
}

async function checkLogin(platform) {
    try {
        const result = await request(`/${platform}/check`, { method: 'POST' });
        showNotice(`${result.platformName}：${statusText(result.status)}`);
        await loadPlatforms();
    } catch (error) {
        showNotice(error.message);
    }
}

async function checkLoggedInPlatforms() {
    const button = document.getElementById('check-logged-in-platforms');
    button.disabled = true;
    button.classList.add('is-loading');
    try {
        const checked = await request('/logged-in/check', { method: 'POST' });
        await loadPlatforms();
        showNotice(checked.length
            ? `已检测 ${checked.length} 个已登录平台：${checked.map(platform => `${platform.platformName} ${statusText(platform.status)}`).join('、')}`
            : '没有需要检测的已登录平台。');
    } catch (error) {
        showNotice(error.message);
    } finally {
        button.disabled = false;
        button.classList.remove('is-loading');
    }
}

async function cancelLogin() {
    if (!activePlatform || cancelPending) return;
    const platform = activePlatform;
    cancelPending = true;
    setLoginBusy(true);
    ++loginGeneration;
    stopPolling();
    // 页面立即退出；服务端浏览器核验可能仍在执行，取消请求在后台等待清理。
    activePlatform = null;
    loginTask.close();
    scanPreview.hidden = true;
    document.getElementById('douyin-verification').hidden = true;
    loginScreenshot.removeAttribute('src');
    showNotice('正在关闭本次登录…');
    try {
        await request(`/${platform}/login/cancel`, { method: 'POST' });
        showNotice('已取消本次登录；数据库中已有登录态未删除。');
    } catch (error) {
        showNotice(`窗口已关闭，但服务端登录任务取消失败：${error.message}`);
    } finally {
        cancelPending = false;
        setLoginBusy(false);
    }
}

document.getElementById('check-logged-in-platforms').addEventListener('click', checkLoggedInPlatforms);
document.getElementById('status-filter').addEventListener('change', filterPlatformRows);
document.getElementById('douyin-receive-sms').addEventListener('click', () =>
    submitLoginAction('identity-verification/RECEIVE_SMS', {}));
document.getElementById('douyin-send-sms').addEventListener('click', () =>
    submitLoginAction('identity-verification/SEND_SMS', {}));
document.getElementById('douyin-verification-code-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const code = document.getElementById('douyin-verification-code');
    await submitLoginAction('identity-verification/sms-code', { code: code.value });
    code.value = '';
});
for (const button of document.querySelectorAll('[data-login-mode]')) {
    button.addEventListener('click', () => switchLoginMode(button.dataset.loginMode));
}
for (const button of document.querySelectorAll('[data-scan-method]')) {
    button.addEventListener('click', () => {
        if (activePlatform && button.dataset.scanMethod !== activeScanMethod) {
            startLogin(activePlatform, button.dataset.scanMethod, 'qr');
        }
    });
}
document.getElementById('send-sms-code').addEventListener('click', async () => {
    const phone = document.getElementById('sms-phone');
    if (!phone.reportValidity()) return;
    await submitLoginAction('sms-code', { phone: phone.value });
});
document.getElementById('sms-login-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const code = document.getElementById('sms-code');
    await submitLoginAction('sms', { code: code.value });
    code.value = '';
});
document.getElementById('password-login-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    const account = document.getElementById('login-account');
    const password = document.getElementById('login-password');
    await submitLoginAction('password', { account: account.value, password: password.value });
    password.value = '';
});
document.getElementById('refresh-login').addEventListener('click', refreshScanImage);
document.getElementById('cancel-login').addEventListener('click', cancelLogin);
document.getElementById('login-close').addEventListener('click', cancelLogin);
loginTask.addEventListener('cancel', (event) => { event.preventDefault(); cancelLogin(); });
window.addEventListener('beforeunload', stopPolling);
loadPlatforms();
