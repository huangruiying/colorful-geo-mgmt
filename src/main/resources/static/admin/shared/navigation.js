"use strict";
/** 管理后台唯一导航定义；根据现有页面路径渲染菜单、高亮、面包屑和退出入口，不参与业务请求。 */
(() => {
    const groups = [
        {title:"项目管理", icon:"◇", pages:[
            {file:"project-create.html", title:"项目创建", icon:"＋"},
            {file:"projects.html", title:"项目列表", icon:"▦"}
        ]},
        {title:"资产诊断", icon:"❖", pages:[
            {file:"diagnose.html", title:"诊断方案", icon:"◉"},
            {file:"diagnosis-tasks.html", title:"诊断任务", icon:"▷"},
            {file:"history-report.html", title:"诊断报告", icon:"▦"}
        ]},
        {title:"内容投放", icon:"▣", pages:[
            {file:"contents.html", title:"内容优化记录", icon:"▤"},
            {file:"publications.html", title:"发布记录", icon:"▧"},
            {file:"effect-recovery.html", title:"效果回收", icon:"↻"}
        ]},
        {title:"基础配置", icon:"⚙", pages:[
            {file:"project-info-import-export.html", title:"数据导入导出", icon:"⇄"},
            {file:"model-config.html", title:"模型配置", icon:"＋"},
            {file:"models.html", title:"模型列表", icon:"▦"},
            {file:"browser-login.html", title:"浏览器登录管理", icon:"◈"}
        ]}
    ];

    /** 从固定导航数据创建文本节点，导航文字不作为 HTML 解析。 */
    function element(tag, className, text) {
        const node = document.createElement(tag);
        if (className) node.className = className;
        if (text != null) node.textContent = text;
        return node;
    }

    /** 菜单装饰图标不进入屏幕阅读器的链接名称。 */
    function icon(text, className = "navigation-icon") {
        const node = element("span", className, text);
        node.setAttribute("aria-hidden", "true");
        return node;
    }

    /** 创建站内页面链接；页面路径保持原值，仅资源发布版本统一维护。 */
    function pageLink(page, className) {
        const link = element("a", className);
        link.href = "./" + page.file + "?v=20261017-layout4";
        return link;
    }

    /** 只展开当前页面所属分组，其他分组仍由原生 details 控制，不引入额外导航状态。 */
    function renderGroup(group, currentFile) {
        const selected = group.pages.some(page => page.file === currentFile);
        const details = element("details", "navigation-group" + (selected ? " active" : ""));
        details.open = selected;
        const summary = element("summary", "navigation-group-label");
        summary.append(icon(group.icon), element("span", null, group.title), icon("›", "navigation-caret"));
        const items = element("div", "navigation-subitems");
        for (const page of group.pages) {
            const active = page.file === currentFile;
            const link = pageLink(page, "navigation-item" + (active ? " active" : ""));
            if (active) link.setAttribute("aria-current", "page");
            link.append(icon(page.icon), element("span", null, page.title));
            items.append(link);
        }
        details.append(summary, items);
        return details;
    }

    /** 渲染当前页面的导航和面包屑；页面标题、正文和业务脚本由原页面保留。 */
    function render(sidebar, currentFile) {
        const brand = pageLink({file:"contents.html"}, "brand");
        brand.setAttribute("aria-label", "GEO 管理平台首页");
        const copy = element("span", "brand-copy");
        copy.append(element("strong", null, "GEO 管理平台"), element("small", null, "CONTENT OPERATIONS"));
        brand.append(icon("G", "brand-mark"), copy);
        const nav = element("nav", "navigation");
        nav.setAttribute("aria-label", "功能菜单");
        nav.append(...groups.map(group => renderGroup(group, currentFile)));
        sidebar.replaceChildren(brand, nav, element("div", "sidebar-footer", "GEO 内容运营工作台"));

        const group = groups.find(group => group.pages.some(page => page.file === currentFile));
        const breadcrumb = document.querySelector(".breadcrumb");
        if (group && breadcrumb) {
            const page = group.pages.find(page => page.file === currentFile);
            breadcrumb.replaceChildren(document.createTextNode(group.title + " "), element("span", null, "/"), document.createTextNode(" "), element("strong", null, page.title));
        }
        renderLogout(document.querySelector(".topbar-label"));
    }

    /** 在顶栏右侧放置退出按钮；页面没有顶栏时不渲染。 */
    function renderLogout(topbarLabel) {
        if (!topbarLabel) return;
        const button = element("button", "logout-button", "退出");
        button.type = "button";
        button.addEventListener("click", logout);
        topbarLabel.append(button);
    }

    /** 以表单提交退出，服务端清除会话后跳转登录页；退出属于写操作，需附带跨站令牌。 */
    function logout() {
        const form = element("form");
        form.method = "post";
        form.action = "../logout";
        const token = element("input");
        token.type = "hidden";
        token.name = "_csrf";
        token.value = window.AdminHttp.csrfToken();
        form.append(token);
        document.body.append(form);
        form.submit();
    }

    const sidebar = document.querySelector("[data-admin-navigation]");
    if (sidebar) render(sidebar, window.location.pathname.split("/").pop());
    window.AdminNavigation = Object.freeze({render});
})();
