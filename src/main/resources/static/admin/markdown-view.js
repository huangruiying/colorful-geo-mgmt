"use strict";
/** 公共正文 Markdown 展示入口；只转换页面视图，不修改原文、编辑框或提交数据。 */
(() => {
    // 禁止原始 HTML；保留普通换行，不启用智能标点转换或自动识别裸链接。
    const parser = window.markdownit({html:false, breaks:true, linkify:false, typographer:false});
    // 先识别公式，避免公式中的独立减号被 Markdown 误认为标题；代码块不参与公式解析。
    // 只输出浏览器原生 MathML，无需远程字体；禁止公式中的 HTML、外链和图片命令。
    parser.use(window.texmath, {engine:window.katex, delimiters:["brackets", "dollars"],
        katexOptions:{output:"mathml", trust:false, strict:"ignore", throwOnError:false, maxExpand:1000, maxSize:20}});
    const validateLink = parser.validateLink.bind(parser);
    /** 只允许网页、邮件和页面内链接；沿用解析器对混淆协议的原有校验。 */
    parser.validateLink = url => {
        if (!validateLink(url)) return false;
        try {
            return ["http:", "https:", "mailto:"].includes(new URL(url, window.location.href).protocol);
        } catch {
            return false;
        }
    };
    /** 新窗口打开正文链接时隔离 opener，锚点链接仍留在当前页面。 */
    parser.renderer.rules.link_open = (tokens, index, options, env, renderer) => {
        if (!tokens[index].attrGet("href").startsWith("#")) {
            tokens[index].attrSet("target", "_blank");
            tokens[index].attrSet("rel", "noopener noreferrer");
        }
        return renderer.renderToken(tokens, index, options);
    };
    const renderImage = parser.renderer.rules.image;
    /** 正文图片按需加载，不向图片来源发送后台页面地址。 */
    parser.renderer.rules.image = (tokens, index, options, env, renderer) => {
        tokens[index].attrSet("loading", "lazy");
        tokens[index].attrSet("referrerpolicy", "no-referrer");
        return renderImage(tokens, index, options, env, renderer);
    };

    /**
     * 将只读正文渲染到指定容器，失败时仍以纯文本展示原文。
     * @param {HTMLElement} container 页面正文容器
     * @param {string} value 接口返回的原文，不在此修改或持久化
     */
    function render(container, value) {
        const source = value == null ? "" : String(value);
        container.classList.add("markdown-body");
        container.classList.remove("markdown-plain-fallback");
        try {
            // HTML 仅来自固定的 Markdown 与公式解析器，不接受正文中的原始 HTML。
            container.innerHTML = parser.render(source);
        } catch {
            container.textContent = source;
            container.classList.add("markdown-plain-fallback");
            console.warn("Markdown 展示失败，已保留原文显示");
        }
    }
    window.MarkdownView = Object.freeze({render});
})();
