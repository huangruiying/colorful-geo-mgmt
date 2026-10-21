const {test} = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

/** 用原生事件验证公共关闭约定；窗口渲染和焦点交由浏览器回归。 */
function createDialogs() {
    class Dialog extends EventTarget {
        open = true;
        getBoundingClientRect() { return {left:10, right:100, top:10, bottom:100}; }
        close() { this.open = false; this.dispatchEvent(new Event("close")); }
    }
    const locked = new Map(), closed = [], parent = new Dialog(), child = new Dialog();
    let allowed = true;
    const context = vm.createContext({window:{}, document:{body:{classList:{toggle(name, value) { locked.set(name, value); }}}}});
    vm.runInContext(fs.readFileSync(path.join(__dirname, "../../main/resources/static/admin/shared/dialog.js"), "utf8"), context);
    context.window.AdminDialogs.bind([parent, child], {canClose:() => allowed, onClose:dialog => closed.push(dialog)});
    return {parent, child, closed, locked, allow(value) { allowed = value; }};
}

test("业务禁止关闭时阻止 ESC；允许关闭时保留原生行为", () => {
    const dialogs = createDialogs();
    dialogs.allow(false);
    const blocked = new Event("cancel", {cancelable:true});
    dialogs.child.dispatchEvent(blocked);
    assert.equal(blocked.defaultPrevented, true);
    dialogs.allow(true);
    const allowed = new Event("cancel", {cancelable:true});
    dialogs.child.dispatchEvent(allowed);
    assert.equal(allowed.defaultPrevented, false);
});

test("只有点击遮罩且业务允许时关闭，不把窗口内点击当成关闭", () => {
    const dialogs = createDialogs();
    dialogs.child.dispatchEvent(Object.assign(new Event("click"), {clientX:20, clientY:20}));
    assert.equal(dialogs.child.open, true);
    dialogs.allow(false);
    dialogs.child.dispatchEvent(Object.assign(new Event("click"), {clientX:0, clientY:0}));
    assert.equal(dialogs.child.open, true);
    dialogs.allow(true);
    dialogs.child.dispatchEvent(Object.assign(new Event("click"), {clientX:0, clientY:0}));
    assert.equal(dialogs.child.open, false);
    assert.equal(dialogs.closed[0], dialogs.child);
});

test("子窗口关闭仍保留滚动锁，最后一个窗口关闭后解除", () => {
    const dialogs = createDialogs();
    dialogs.child.close();
    assert.equal(dialogs.locked.get("task-dialog-open"), true);
    dialogs.parent.close();
    assert.equal(dialogs.locked.get("task-dialog-open"), false);
});

test("迟到的 close 事件不清理已重新打开的窗口", () => {
    const dialogs = createDialogs();
    dialogs.child.dispatchEvent(new Event("close"));
    assert.equal(dialogs.closed.length, 0);
});
