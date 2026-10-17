"use strict";
/** 只读展示格式；不转换接口数据，不修改存储时间或时区。 */
(() => {
    /** 沿用浏览器本地时区、中文24小时制；缺失或非法时间统一显示占位符。 */
    function dateTime(value) {
        if (value == null || value === "") {
            return "—";
        }
        const date = new Date(value);
        return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString("zh-CN", {hour12:false});
    }
    window.AdminFormat = Object.freeze({dateTime});
})();
