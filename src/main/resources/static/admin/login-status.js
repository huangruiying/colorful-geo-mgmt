/** 平台登录状态页：读取同源接口，并区分未登录、未知和查询失败。 */
const loginStatusEndpoint = "/publication/platforms/login-status";
const statusLabels = {
    LOGGED_IN: "已登录",
    NOT_LOGGED_IN: "未登录",
    UNKNOWN: "状态未知"
};

const refreshButton = document.querySelector("#refresh-button");
const platformRows = document.querySelector("#platform-rows");
const notice = document.querySelector("#notice");
const updatedAt = document.querySelector("#updated-at");

refreshButton.addEventListener("click", loadPlatformLoginStatuses);
loadPlatformLoginStatuses();

/** 查询已接入的平台；接口失败时保留上一次成功展示的结果。 */
async function loadPlatformLoginStatuses() {
    refreshButton.disabled = true;
    refreshButton.classList.add("is-loading");
    showNotice("正在查询平台登录状态…", "loading");

    try {
        const response = await fetch(loginStatusEndpoint, {
            headers: {Accept: "application/json"},
            cache: "no-store"
        });
        if (!response.ok) {
            throw new Error(`接口返回 HTTP ${response.status}`);
        }

        const platforms = await response.json();
        if (!Array.isArray(platforms)) {
            throw new Error("接口未返回平台列表");
        }

        renderPlatformRows(platforms);
        renderStatusSummary(platforms);
        updatedAt.textContent = `更新于 ${new Intl.DateTimeFormat("zh-CN", {
            hour: "2-digit", minute: "2-digit", second: "2-digit"
        }).format(new Date())}`;

        // 全部未知通常意味着桥接不可用；不能把它误报成“未登录”。
        if (platforms.length > 0 && platforms.every(platform => platform.status === "UNKNOWN")) {
            showNotice("暂时无法确认各平台的登录状态，请检查 Chrome、文章同步助手及桥接连接后重试。", "warning");
        } else {
            hideNotice();
        }
    } catch (error) {
        showNotice(`查询失败：${error.message}。请确认服务正常运行后重试。`, "error");
        if (platformRows.querySelector(".table-message")) {
            showTableMessage("暂时无法获取平台列表，请点击“刷新状态”重试。");
        }
    } finally {
        refreshButton.disabled = false;
        refreshButton.classList.remove("is-loading");
    }
}

/** 将接口的明确结论映射到展示状态，未识别的状态一律按未知处理。 */
function normalizeLoginStatus(status) {
    return Object.hasOwn(statusLabels, status) ? status : "UNKNOWN";
}

/** 使用文本节点展示平台数据，避免账号名或平台名称被当作 HTML 执行。 */
function renderPlatformRows(platforms) {
    if (platforms.length === 0) {
        showTableMessage("尚未接入可展示的发布平台。");
        return;
    }

    const rows = platforms.map(platform => {
        const row = document.createElement("tr");
        const platformCell = document.createElement("td");
        const name = document.createElement("strong");
        name.textContent = platform.displayName || platform.platformType || "未命名平台";
        const identifier = document.createElement("small");
        identifier.textContent = platform.platformType || "—";
        platformCell.className = "platform-name";
        platformCell.append(name, identifier);

        const status = normalizeLoginStatus(platform.status);
        const statusCell = document.createElement("td");
        const badge = document.createElement("span");
        badge.className = `status-badge status-${status.toLowerCase().replaceAll("_", "-")}`;
        badge.textContent = statusLabels[status];
        statusCell.append(badge);

        const accountCell = document.createElement("td");
        accountCell.className = "account-name";
        accountCell.textContent = status === "LOGGED_IN" && platform.username ? platform.username : "—";
        row.append(platformCell, statusCell, accountCell);
        return row;
    });
    platformRows.replaceChildren(...rows);
}

/** 概览与列表使用同一份接口结果，避免数量和明细不一致。 */
function renderStatusSummary(platforms) {
    const counts = {LOGGED_IN: 0, NOT_LOGGED_IN: 0, UNKNOWN: 0};
    platforms.forEach(platform => counts[normalizeLoginStatus(platform.status)]++);
    document.querySelector("#total-count").textContent = platforms.length;
    document.querySelector("#logged-in-count").textContent = counts.LOGGED_IN;
    document.querySelector("#not-logged-in-count").textContent = counts.NOT_LOGGED_IN;
    document.querySelector("#unknown-count").textContent = counts.UNKNOWN;
}

function showTableMessage(message) {
    const cell = document.createElement("td");
    cell.className = "table-message";
    cell.colSpan = 3;
    cell.textContent = message;
    const row = document.createElement("tr");
    row.append(cell);
    platformRows.replaceChildren(row);
}

function showNotice(message, type) {
    notice.textContent = message;
    notice.className = `notice notice-${type}`;
    notice.hidden = false;
}

function hideNotice() {
    notice.hidden = true;
}
