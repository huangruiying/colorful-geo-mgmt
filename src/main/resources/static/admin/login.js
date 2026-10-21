"use strict";
/** 登录页：按服务端跳转带回的参数提示结果，提交时附带跨站令牌；账号校验与锁定由服务端完成，不提供注册。 */
(() => {
    /** 服务端跳回登录页时携带的结果参数与对应提示；退出登录不是错误，用普通样式展示。 */
    const messages = [
        ["locked", "登录失败次数过多，请 15 分钟后再试", true],
        ["error", "用户名或密码错误", true],
        ["expired", "登录已过期，请重新登录", true],
        ["logout", "已退出登录", false]
    ];

    const params = new URLSearchParams(window.location.search);
    const matched = messages.find(([key]) => params.has(key));
    if (matched) {
        const node = document.getElementById("login-message");
        node.textContent = matched[1];
        node.classList.toggle("error", matched[2]);
        node.hidden = false;
    }

    // 令牌由服务端在打开登录页时写入 Cookie，提交前再读取，避免页面停留期间令牌更换
    document.getElementById("login-form").addEventListener("submit", () => {
        document.getElementById("csrf-token").value = window.AdminHttp.csrfToken();
    });
})();
