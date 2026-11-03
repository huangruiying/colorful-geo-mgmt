package org.huangry.colorful.geo.infrastructure.client.playwright.takeover;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 接管记录在窗口与后台线程之间交换画面、操作的规则。 */
class BrowserTakeoverTest {

    private final Instant startedAt = Instant.now();
    private final BrowserTakeover takeover = new BrowserTakeover("id-1", "搜狐号 · 预发布", "原因",
            startedAt, startedAt.plusSeconds(600));

    /** 没人取画面时不算在看；取过一次后才算，后台线程据此开始截图。 */
    @Test
    void shouldMarkViewerActiveOnlyAfterFrameRead() {
        assertFalse(takeover.isViewerActive());

        assertTrue(takeover.readLatestFrame().isEmpty());

        assertTrue(takeover.isViewerActive());
    }

    /** 只保留最新一帧，序号递增供窗口跳过旧帧。 */
    @Test
    void shouldKeepLatestFrameWithIncreasingSequence() {
        takeover.updateFrame("first", 1280, 900);
        takeover.updateFrame("second", 1280, 900);

        BrowserTakeoverFrame frame = takeover.readLatestFrame().orElseThrow();

        assertEquals("second", frame.imageBase64());
        assertEquals(2, frame.sequence());
    }

    /** 一批操作按提交顺序取出。 */
    @Test
    void shouldPollInputsInSubmittedOrder() {
        takeover.addInputs(List.of(mouseInput(BrowserTakeoverInput.Type.MOUSE_PRESSED, 0, 1),
                mouseInput(BrowserTakeoverInput.Type.MOUSE_RELEASED, 80, 1)));

        assertEquals(BrowserTakeoverInput.Type.MOUSE_PRESSED, takeover.pollInput().type());
        assertEquals(BrowserTakeoverInput.Type.MOUSE_RELEASED, takeover.pollInput().type());
    }

    /** 页面卡住、积压到 500 条时，放不下的一批整批拒绝，不只放入半批；正好放满仍可放入。 */
    @Test
    void shouldRejectWholeBatchBeyondQueueLimit() {
        BrowserTakeoverInput move = mouseInput(BrowserTakeoverInput.Type.MOUSE_MOVED, 0, 1);
        takeover.addInputs(Collections.nCopies(499, move));

        var exception = assertThrows(IllegalArgumentException.class, () -> takeover.addInputs(List.of(move, move)));
        takeover.addInputs(List.of(move));

        assertEquals("页面暂未响应，请稍后再操作", exception.getMessage());
    }

    /** 到达期限即视为超时。 */
    @Test
    void shouldExpireAtDeadline() {
        assertFalse(takeover.isExpired(startedAt.plusSeconds(599)));
        assertTrue(takeover.isExpired(startedAt.plusSeconds(600)));
    }

    /**
     * 不合法的操作在进入队列前拒绝：类型或发生时间缺失、坐标越界、文字超长、未放行的按键；
     * 一批里只要有一个不合法，前面合法的也不放入。
     */
    @Test
    void shouldRejectInvalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> takeover.addInputs(List.of(
                new BrowserTakeoverInput(null, 0.0, 0, 0, 0, 0, null, null))));
        assertThrows(IllegalArgumentException.class, () -> takeover.addInputs(List.of(
                new BrowserTakeoverInput(BrowserTakeoverInput.Type.MOUSE_MOVED, null, 0, 0, 0, 0, null, null))));
        assertThrows(IllegalArgumentException.class, () -> takeover.addInputs(List.of(
                mouseInput(BrowserTakeoverInput.Type.MOUSE_MOVED, 0, -1))));
        assertThrows(IllegalArgumentException.class, () -> takeover.addInputs(List.of(
                new BrowserTakeoverInput(BrowserTakeoverInput.Type.INSERT_TEXT, 0.0, 0, 0, 0, 0, "x".repeat(101), null))));
        assertThrows(IllegalArgumentException.class, () -> takeover.addInputs(List.of(
                mouseInput(BrowserTakeoverInput.Type.MOUSE_PRESSED, 0, 1),
                new BrowserTakeoverInput(BrowserTakeoverInput.Type.KEY_PRESS, 10.0, 0, 0, 0, 0, null, "F5"))));
        assertNull(takeover.pollInput());
    }

    /**
     * 鼠标操作。
     *
     * @param type 操作类型
     * @param clientTimeMillis 窗口记录的发生时间
     * @param x 横坐标，纵坐标与之相同
     * @return 用户操作
     */
    private static BrowserTakeoverInput mouseInput(BrowserTakeoverInput.Type type, double clientTimeMillis, double x) {
        return new BrowserTakeoverInput(type, clientTimeMillis, x, x, 0, 0, null, null);
    }
}
