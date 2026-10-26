const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs"), path = require("node:path"), vm = require("node:vm");

/** 保留节点树和属性，验证导航结构；布局与原生details行为另由浏览器检查。 */
function createNavigation() {
    const element = tag => ({tag, children:[], attributes:{}, textContent:"",
        append(...children) { this.children.push(...children); },
        replaceChildren(...children) { this.children = children; },
        setAttribute(name, value) { this.attributes[name] = value; }
    });
    const sidebar = element("aside"), breadcrumb = element("div");
    const context = vm.createContext({window:{location:{pathname:"/admin/projects.html"}}, document:{
        createElement:element, createTextNode:text => ({textContent:text}),
        querySelector:selector => selector === ".breadcrumb" ? breadcrumb : null
    }});
    vm.runInContext(fs.readFileSync(path.join(__dirname, "../../main/resources/static/admin/shared/navigation.js"), "utf8"), context);
    const nodes = root => [root, ...root.children?.flatMap(nodes) || []];
    return {sidebar, breadcrumb, nodes, render:context.window.AdminNavigation.render};
}

const pages = ["projects", "project-create", "models", "model-config", "diagnose", "diagnosis-tasks", "history-report", "contents", "publications", "browser-login", "effect-recovery", "project-info-import-export"];
for (const page of pages) {
    test(`${page} 只有一个当前导航且展开所属分组，包含项目数据迁移入口`, () => {
        const nav = createNavigation(); nav.render(nav.sidebar, page + ".html");
        const all = nav.nodes(nav.sidebar), current = all.filter(node => node.attributes?.["aria-current"] === "page");
        assert.equal(current.length, 1);
        assert.equal(current[0].href.split("?")[0], "./" + page + ".html");
        assert.equal(all.filter(node => node.tag === "details" && node.open).length, 1);
        const links = all.filter(node => node.className?.startsWith("navigation-item"));
        assert.equal(links.length, pages.length);
        assert.deepEqual(links.map(node => node.href.split("?")[0]).sort(), pages.map(file => "./" + file + ".html").sort());
        const title = nav.breadcrumb.children.find(node => node.tag === "strong");
        assert.equal(title.textContent, current[0].children.at(-1).textContent);
    });
}

test("重复渲染不累积菜单，品牌首页和图标可访问性统一", () => {
    const nav = createNavigation();
    nav.render(nav.sidebar, "projects.html"); nav.render(nav.sidebar, "models.html");
    const all = nav.nodes(nav.sidebar);
    assert.equal(all.filter(node => node.tag === "nav").length, 1);
    assert.equal(nav.sidebar.children[0].href.split("?")[0], "./contents.html");
    for (const node of all.filter(node => ["brand-mark", "navigation-icon", "navigation-caret"].includes(node.className))) {
        assert.equal(node.attributes["aria-hidden"], "true");
    }
});
