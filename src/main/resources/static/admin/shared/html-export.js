"use strict";
/** 把一段页面内容保存为可离线打开的独立 HTML 文件；不请求接口，内容与文件名由业务模块决定。 */
(() => {
    /**
     * 生成独立网页并触发浏览器下载：内联当前页面的样式，去掉无法在静态网页中使用的按钮。
     * @param {HTMLElement} content 要导出的完整内容；导出的是副本，页面上的内容不受影响
     * @param {{title:string, bodyClass:string}} options title 同时作为网页标题和下载文件名；bodyClass 让页面样式在导出网页中同样生效
     */
    function downloadPage(content, {title, bodyClass}) {
        // 1. 复制内容并去掉按钮：静态网页没有脚本，按钮点击后不会有任何反应
        const copy = content.cloneNode(true);
        copy.querySelectorAll("button").forEach(button => button.remove());

        // 2. 用独立文档组装网页，标题与正文经 DOM 序列化转义，不拼接 HTML 字符串
        const page = document.implementation.createHTMLDocument(title);
        const charset = page.createElement("meta");
        charset.setAttribute("charset", "UTF-8");
        const viewport = page.createElement("meta");
        viewport.name = "viewport";
        viewport.content = "width=device-width, initial-scale=1";
        const style = page.createElement("style");
        style.textContent = collectPageStyles();
        page.head.prepend(charset, viewport);
        page.head.append(style);
        page.body.className = bodyClass;
        page.body.append(page.importNode(copy, true));

        // 3. 以文件形式下载；部分浏览器在点击后才异步读取文件，下载地址延后释放
        const html = "<!doctype html>\n" + page.documentElement.outerHTML;
        const url = URL.createObjectURL(new Blob([html], {type:"text/html;charset=utf-8"}));
        const link = document.createElement("a");
        link.href = url;
        link.download = title + ".html";
        document.body.append(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url));
    }

    /** @return 当前页面已加载的全部样式规则文本；导出网页脱离站点打开，外链样式无法加载，需要内联 */
    function collectPageStyles() {
        const rules = [];
        for (const sheet of document.styleSheets) {
            for (const rule of sheet.cssRules) {
                rules.push(rule.cssText);
            }
        }
        return rules.join("\n");
    }

    window.AdminHtmlExport = Object.freeze({downloadPage});
})();
