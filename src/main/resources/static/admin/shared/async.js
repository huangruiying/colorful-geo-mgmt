"use strict";
/** 页面读取请求的归属与轮询生命周期；只取消浏览器等待，不取消后台任务或重试写操作。 */
(() => {
    /** 创建一个请求作用域；新读取取代旧读取，过期请求的结果和错误均不能进入当前视图。 */
    function createRequestScope() {
        let controller = null;

        /** 使本次读取失效并终止网络等待，供切换对象或关闭窗口时调用。 */
        function cancel() {
            const previous = controller;
            controller = null;
            previous?.abort();
        }

        /**
         * 执行当前读取；回调在每次 await 后用 isCurrent 校验再写视图。
         * @param {function({signal:AbortSignal, isCurrent:function():boolean}):Promise<*>} read 读取及视图更新
         */
        async function run(read) {
            cancel();
            const current = new AbortController();
            controller = current;
            const isCurrent = () => controller === current && !current.signal.aborted;
            try {
                await read({signal:current.signal, isCurrent});
            } catch (error) {
                // 过期读取的失败不再提示到另一个对象或已关闭窗口；当前失败仍交页面处理。
                if (isCurrent()) {
                    throw error;
                }
            }
        }
        return Object.freeze({run, cancel});
    }

    /**
     * 创建串行读轮询；read 返回 true 才继续，手动刷新取代旧读取，stop 同时清理计时器和请求。
     * @param {function(*):Promise<boolean>} read 读取并更新视图，接收请求作用域的 signal/isCurrent
     * @param {function(Error):void} onError 当前读取失败时的页面提示
     */
    function createPoller(read, onError) {
        const scope = createRequestScope();
        let timer = null;

        /** 立即读一次，成功后等 3 秒；网络、限流或服务端错误等 5 秒再读，业务错误停止。 */
        async function refresh() {
            clearTimeout(timer);
            timer = null;
            await scope.run(async ticket => {
                try {
                    const continuing = await read(ticket);
                    if (ticket.isCurrent() && continuing) {
                        timer = setTimeout(refresh, 3000);
                    }
                } catch (error) {
                    if (!ticket.isCurrent()) {
                        return;
                    }
                    onError(error);
                    // 仅重试读请求；404/参数拒绝不会因自动刷新持续请求。
                    if (ticket.isCurrent() && (error.status == null || error.status === 429 || error.status >= 500)) {
                        timer = setTimeout(refresh, 5000);
                    }
                }
            });
        }

        /** 页面离开或窗口关闭时停止；即使旧响应忽略 abort 返回，也无法恢复轮询。 */
        function stop() {
            clearTimeout(timer);
            timer = null;
            scope.cancel();
        }
        return Object.freeze({refresh, stop});
    }
    window.AdminAsync = Object.freeze({createRequestScope, createPoller});
})();
