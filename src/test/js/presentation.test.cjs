const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs"), path = require("node:path"), vm = require("node:vm");

/** 加载实际展示函数，只提供最小 DOM；不替换链接校验或草稿地址规则。 */
function createView() {
    const context = vm.createContext({window:{}, URL, document:{createElement:tag => ({tag})}});
    for (const file of ["shared/format.js", "publishing/status.js", "url-utils.js"]) {
        vm.runInContext(fs.readFileSync(path.join(__dirname, "../../main/resources/static/admin", file), "utf8"), context);
    }
    return context;
}

test("内容日期保留本地时区和24小时格式，缺失及非法日期不显示1970或Invalid Date", () => {
    const view = createView(), format = view.window.AdminFormat.dateTime;
    for (const value of [null, undefined, "", "invalid-date"]) assert.equal(format(value), "—");
    const date = "2026-10-17T11:30:00+08:00";
    assert.equal(format(date), new Date(date).toLocaleString("zh-CN", {hour12:false}));
});

test("发布文案字典只负责展示，所有原有状态保留且不可变", () => {
    const labels = createView().window.PublishStatus.labels;
    assert.equal(Object.keys(labels).length, 8);
    assert.equal(labels.PRE_PUBLISH_FAILED, "预发布失败");
    assert.equal(labels.PUBLISHED, "实际发布成功");
    assert.equal(Object.isFrozen(labels), true);
});

test("网页链接保留安全属性与纯文本标题，非网页地址不插入", () => {
    const view = createView(), children = [], container = {append:node => children.push(node)};
    const link = view.appendWebLink(container, "https://example.invalid/a", "<script>只作文本</script>");
    assert.equal(link.href, "https://example.invalid/a");
    assert.equal(link.textContent, "<script>只作文本</script>");
    assert.equal(link.target, "_blank"); assert.equal(link.rel, "noopener noreferrer");
    for (const url of ["javascript:alert(1)", "data:text/html,test", "file:///test", "invalid"]) {
        assert.equal(view.appendWebLink(container, url, "无效"), null);
    }
    assert.equal(children.length, 1);
});

test("公众号入口、已有深链和补充草稿ID的规则不变", () => {
    const view = createView(), children = [], container = {append:node => children.push(node)};
    view.appendDraftEntry(container, {platformType:"WECHAT_OFFICIAL_ACCOUNT", remoteContentId:"test-draft"});
    assert.match(children[0].href, /^https:\/\/mp\.weixin\.qq\.com\/cgi-bin\/appmsg\?/);
    assert.match(children[0].title, /自行选择草稿/);
    const deep = "https://example.invalid/editor?article_id=123";
    assert.equal(view.withDraftId(deep, "123"), deep);
    const filled = view.withDraftId("https://example.invalid/index.html#/", "123");
    assert.equal(new URL(filled).hash, "#/?id=123");
});
