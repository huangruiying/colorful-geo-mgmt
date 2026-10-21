"use strict";
/** 原生 dialog 的关闭入口和滚动锁；业务模块负责保存保护、子窗口与请求清理。 */
(() => {
    /**
     * 为一组窗口绑定 ESC、遮罩和关闭清理；按钮关闭仍使用原生 close，统一触发 onClose。
     * @param {HTMLDialogElement[]} dialogs 当前页面的窗口
     * @param {{canClose:function(HTMLDialogElement):boolean, onClose:function(HTMLDialogElement):void}} options 业务关闭条件和清理动作
     */
    function bind(dialogs, {canClose, onClose}) {
        for (const dialog of dialogs) {
            dialog.addEventListener("cancel", event => {
                if (!canClose(dialog)) {
                    event.preventDefault();
                }
            });
            dialog.addEventListener("click", event => {
                const bounds = dialog.getBoundingClientRect();
                const outside = event.clientX < bounds.left || event.clientX > bounds.right
                    || event.clientY < bounds.top || event.clientY > bounds.bottom;
                if (event.target === dialog && outside && canClose(dialog)) {
                    dialog.close();
                }
            });
            dialog.addEventListener("close", () => {
                // 原生 close 事件异步派发；同一窗口已重新打开时不清理新一轮读取。
                if (dialog.open) {
                    return;
                }
                // 子窗口的清理由业务决定；清理后再按实际打开的窗口恢复背景滚动。
                onClose(dialog);
                document.body.classList.toggle("task-dialog-open", dialogs.some(item => item.open));
            });
        }
    }
    window.AdminDialogs = Object.freeze({bind});
})();
