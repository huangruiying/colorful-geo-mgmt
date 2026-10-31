"use strict";
/**
 * 全局浏览器人工接管窗口：服务器上的自动化浏览器需要人工操作时（如平台验证），在任意管理页弹出接管窗口，
 * 由用户本人在服务器页面的实时画面上操作。只展示画面、转发鼠标键盘操作，不替用户判断或操作。
 * 依赖 shared/http.js；一次只展示一条，处理完自动轮到下一条。
 */
(() => {
    /** 查询待接管列表的间隔。 */
    const LIST_INTERVAL_MS = 3000;
    /** 取画面的间隔（约每秒 12 次）；服务端每 100ms 才确认一次截图，新画面最多约每秒 10 帧。 */
    const FRAME_INTERVAL_MS = 80;
    /** 允许转发的功能键，与服务端白名单一致；字符输入走文字输入。 */
    const SPECIAL_KEYS = new Set(["Backspace", "Tab", "Enter", "Escape", "Delete",
        "ArrowLeft", "ArrowUp", "ArrowRight", "ArrowDown"]);

    /** 当前打开的接管；null 表示窗口未打开。 */
    let current = null;
    /** 本页内点过“稍后处理”的接管，刷新页面或换页后会再次弹出。 */
    const postponedIds = new Set();
    let dialog, title, reason, countdown, canvas, status;

    /** 创建窗口结构；文字一律用 textContent，不解析服务端内容为 HTML。 */
    function createDialog() {
        dialog = document.createElement("dialog");
        dialog.className = "modal modal-wide browser-takeover-dialog";
        const body = document.createElement("div");
        body.className = "modal-body";
        const heading = document.createElement("div");
        heading.className = "modal-heading";
        const headingText = document.createElement("div");
        title = document.createElement("h2");
        reason = document.createElement("p");
        headingText.append(title, reason);
        countdown = document.createElement("span");
        countdown.className = "browser-takeover-countdown";
        heading.append(headingText, countdown);
        canvas = document.createElement("canvas");
        canvas.className = "browser-takeover-canvas";
        canvas.tabIndex = 0;
        status = document.createElement("p");
        status.className = "browser-takeover-status";
        const actions = document.createElement("div");
        actions.className = "modal-actions";
        const postpone = actionButton("稍后处理", "text-button", () => closeCurrent(true));
        const abandon = actionButton("放弃接管", "text-button", abandonCurrent);
        actions.append(abandon, postpone);
        body.append(heading, status, canvas, actions);
        dialog.append(body);
        // ESC 留给远程页面使用，不关闭窗口；关闭只走按钮
        dialog.addEventListener("cancel", event => event.preventDefault());
        bindCanvasInput();
        document.body.append(dialog);
    }

    /** 创建窗口底部按钮。 */
    function actionButton(text, className, onClick) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = className;
        button.textContent = text;
        button.addEventListener("click", onClick);
        return button;
    }

    /** 定时查询待接管列表；窗口已打开或页面在后台时跳过，不打扰当前操作。 */
    async function pollTakeovers() {
        try {
            if (!current && document.visibilityState === "visible") {
                const takeovers = await AdminHttp.requestJson("/browser-takeovers");
                const next = (takeovers || []).find(item => !postponedIds.has(item.takeoverId));
                if (next) openTakeover(next);
            }
        } catch (error) {
            // 查询失败不影响所在页面，下一轮再试
        } finally {
            setTimeout(pollTakeovers, LIST_INTERVAL_MS);
        }
    }

    /** 打开一条接管的窗口，开始取画面。 */
    function openTakeover(takeover) {
        if (!dialog) createDialog();
        current = {takeover, lastSequence: 0, frameWidth: 0, frameHeight: 0, inputs: [], sending: false};
        title.textContent = `需要人工接管：${takeover.title}`;
        reason.textContent = `${takeover.reason}。请在下方画面中操作，完成后自动继续。`;
        status.textContent = "正在连接服务器浏览器…";
        canvas.width = 0;
        dialog.showModal();
        document.body.classList.add("task-dialog-open");
        refreshFrame(current);
    }

    /**
     * 关闭当前窗口。
     * @param {boolean} postpone 用户选择稍后处理时为 true，本页不再自动弹出这条
     */
    function closeCurrent(postpone) {
        if (current && postpone) postponedIds.add(current.takeover.takeoverId);
        current = null;
        dialog.close();
        document.body.classList.remove("task-dialog-open");
    }

    /** 放弃接管；服务器上的业务随即按失败处理。 */
    async function abandonCurrent() {
        if (!current || !window.confirm("放弃后本次操作将记为失败，确定放弃？")) return;
        const id = current.takeover.takeoverId;
        try {
            await AdminHttp.requestJson(`/browser-takeovers/${encodeURIComponent(id)}/abandon`, {method: "POST"});
        } catch (error) {
            // 404 说明接管已结束，同样关闭窗口
            if (error.status !== 404) {
                status.textContent = error.message;
                return;
            }
        }
        closeCurrent(false);
    }

    /**
     * 拉取最新一帧并绘制；接管已结束（404）时关闭窗口。
     * @param {object} session 发起本轮拉取时的接管，窗口已换成别的接管时停止
     */
    async function refreshFrame(session) {
        if (current !== session) return;
        try {
            const frame = await AdminHttp.requestJson(
                `/browser-takeovers/${encodeURIComponent(session.takeover.takeoverId)}/frame`);
            if (frame && frame.sequence > session.lastSequence && current === session) {
                await drawFrame(session, frame);
            }
        } catch (error) {
            if (error.status === 404 && current === session) {
                closeCurrent(false);
                return;
            }
            status.textContent = error.message;
        }
        updateCountdown(session);
        setTimeout(() => refreshFrame(session), FRAME_INTERVAL_MS);
    }

    /** 把 JPEG 帧画到画布上，并记下页面尺寸供坐标换算。 */
    function drawFrame(session, frame) {
        return new Promise(resolve => {
            const image = new Image();
            image.onload = () => {
                if (current === session) {
                    canvas.width = image.naturalWidth;
                    canvas.height = image.naturalHeight;
                    canvas.getContext("2d").drawImage(image, 0, 0);
                    session.lastSequence = frame.sequence;
                    session.frameWidth = frame.width;
                    session.frameHeight = frame.height;
                    status.textContent = "画面为服务器浏览器实时画面，可直接点击、拖动和输入。";
                }
                resolve();
            };
            image.onerror = () => resolve();
            image.src = "data:image/jpeg;base64," + frame.imageBase64;
        });
    }

    /** 显示剩余等待时间，到期后服务器按超时处理。 */
    function updateCountdown(session) {
        const remainingSeconds = Math.max(0, Math.round((Date.parse(session.takeover.expiresAt) - Date.now()) / 1000));
        countdown.textContent = `剩余 ${Math.floor(remainingSeconds / 60)}:${String(remainingSeconds % 60).padStart(2, "0")}`;
    }

    /** 画布上的鼠标、滚轮、键盘、粘贴操作都转发到服务器页面。 */
    function bindCanvasInput() {
        canvas.addEventListener("pointerdown", event => {
            if (event.button !== 0) return;
            canvas.focus();
            canvas.setPointerCapture(event.pointerId);
            enqueueInput({type: "MOUSE_PRESSED", ...pagePoint(event)});
            event.preventDefault();
        });
        canvas.addEventListener("pointermove", event => enqueueInput({type: "MOUSE_MOVED", ...pagePoint(event)}));
        canvas.addEventListener("pointerup", event => {
            if (event.button !== 0) return;
            enqueueInput({type: "MOUSE_RELEASED", ...pagePoint(event)});
        });
        canvas.addEventListener("wheel", event => {
            enqueueInput({type: "MOUSE_WHEEL", ...pagePoint(event), deltaX: event.deltaX, deltaY: event.deltaY});
            event.preventDefault();
        }, {passive: false});
        canvas.addEventListener("keydown", event => {
            if (event.ctrlKey || event.metaKey || event.altKey) return;
            if (event.key.length === 1) {
                enqueueInput({type: "INSERT_TEXT", text: event.key});
            } else if (SPECIAL_KEYS.has(event.key)) {
                enqueueInput({type: "KEY_PRESS", key: event.key});
            } else {
                return;
            }
            event.preventDefault();
        });
        // 验证码等短文本可从剪贴板粘贴
        canvas.addEventListener("paste", event => {
            const text = event.clipboardData?.getData("text/plain")?.trim();
            if (text) enqueueInput({type: "INSERT_TEXT", text: text.slice(0, 100)});
            event.preventDefault();
        });
    }

    /** 把画布上的位置按显示缩放换算成服务器页面坐标。 */
    function pagePoint(event) {
        const bounds = canvas.getBoundingClientRect();
        if (!current || !current.frameWidth || !bounds.width) return {x: 0, y: 0};
        const x = (event.clientX - bounds.left) / bounds.width * current.frameWidth;
        const y = (event.clientY - bounds.top) / bounds.height * current.frameHeight;
        return {x: Math.min(Math.max(x, 0), current.frameWidth), y: Math.min(Math.max(y, 0), current.frameHeight)};
    }

    /**
     * 按顺序逐个发送操作，保证按下、拖动、松开的先后；连续的移动只保留最新一次，避免拖动积压。
     * @param {object} input 操作，字段与服务端 BrowserTakeoverInput 一致
     */
    function enqueueInput(input) {
        if (!current || !current.frameWidth) return;
        const session = current;
        const last = session.inputs[session.inputs.length - 1];
        if (input.type === "MOUSE_MOVED" && last?.type === "MOUSE_MOVED") {
            session.inputs[session.inputs.length - 1] = input;
        } else {
            session.inputs.push(input);
        }
        sendInputs(session);
    }

    /** 串行发送队列中的操作；失败时提示，不重发，避免同一操作在页面上执行两次。 */
    async function sendInputs(session) {
        if (session.sending) return;
        session.sending = true;
        try {
            while (session.inputs.length && current === session) {
                const input = session.inputs.shift();
                await AdminHttp.requestJson(
                    `/browser-takeovers/${encodeURIComponent(session.takeover.takeoverId)}/input`,
                    {method: "POST", body: JSON.stringify(input)});
            }
        } catch (error) {
            session.inputs.length = 0;
            if (error.status !== 404) status.textContent = error.message;
        } finally {
            session.sending = false;
        }
    }

    // 部分页面在本脚本之后才引入 http.js；所有 defer 脚本执行完才触发 DOMContentLoaded
    document.addEventListener("DOMContentLoaded", pollTakeovers);
})();
