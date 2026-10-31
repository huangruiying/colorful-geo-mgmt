package org.huangry.colorful.geo.infrastructure.client.playwright.takeover;

import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 浏览器人工接管的登记、等待与结局：页面与 CDP 通道用替身，不启动浏览器；完成条件由测试直接给出。 */
class BrowserTakeoverRegistryTest {

    private static final String TITLE = "搜狐号 · 预发布";
    private static final String REASON = "搜狐号要求对本浏览器做短信安全验证";

    private final BrowserTakeoverRegistry registry = createRegistry(600);
    private final Page page = mock(Page.class);
    private final CDPSession cdpSession = mock(CDPSession.class);

    /** 构造登记表并设置等待期限。 */
    private static BrowserTakeoverRegistry createRegistry(long timeoutSeconds) {
        BrowserTakeoverRegistry registry = new BrowserTakeoverRegistry();
        ReflectionTestUtils.setField(registry, "timeoutSeconds", timeoutSeconds);
        return registry;
    }

    /** 页面替身只需能打开 CDP 通道。 */
    @BeforeEach
    void stubPage() {
        BrowserContext context = mock(BrowserContext.class);
        when(page.context()).thenReturn(context);
        when(context.newCDPSession(page)).thenReturn(cdpSession);
    }

    /** 完成条件满足即结束；等待期间可在登记表查到调用方给出的标题和原因，结束后移除。 */
    @Test
    void shouldCompleteWhenCompletionConditionIsMet() {
        AtomicInteger checks = new AtomicInteger();
        AtomicReference<BrowserTakeover> listedDuringWait = new AtomicReference<>();
        doAnswer(invocation -> {
            listedDuringWait.set(registry.listTakeovers().get(0));
            return null;
        }).when(page).waitForTimeout(anyDouble());

        BrowserTakeoverOutcome outcome = registry.requestTakeover(page, TITLE, REASON,
                current -> checks.incrementAndGet() >= 2);

        assertEquals(BrowserTakeoverOutcome.COMPLETED, outcome);
        assertEquals(TITLE, listedDuringWait.get().getTitle());
        assertEquals(REASON, listedDuringWait.get().getReason());
        assertTrue(registry.listTakeovers().isEmpty());
        verify(cdpSession).detach();
    }

    /** 页面跳转中读不到时按未完成处理，下一轮再判断，不因此失败。 */
    @Test
    void shouldTreatUnreadablePageAsNotCompleted() {
        AtomicInteger checks = new AtomicInteger();

        BrowserTakeoverOutcome outcome = registry.requestTakeover(page, TITLE, REASON, current -> {
            if (checks.incrementAndGet() == 1) {
                throw new PlaywrightException("Execution context was destroyed");
            }
            return true;
        });

        assertEquals(BrowserTakeoverOutcome.COMPLETED, outcome);
        verify(page, times(1)).waitForTimeout(anyDouble());
    }

    /** 用户放弃后结束等待，放弃前积压的操作不再作用到页面。 */
    @Test
    void shouldStopWithoutDispatchingQueuedInputsWhenAbandoned() {
        doAnswer(invocation -> {
            BrowserTakeover takeover = registry.listTakeovers().get(0);
            takeover.addInput(new BrowserTakeoverInput(BrowserTakeoverInput.Type.INSERT_TEXT, 0, 0, 0, 0, "123456", null));
            takeover.abandon();
            return null;
        }).when(page).waitForTimeout(anyDouble());

        BrowserTakeoverOutcome outcome = registry.requestTakeover(page, TITLE, REASON, current -> false);

        assertEquals(BrowserTakeoverOutcome.ABANDONED, outcome);
        verify(cdpSession, never()).send(eq("Input.insertText"), any(JsonObject.class));
    }

    /** 等待期限已到时按超时结束，不再检查完成条件。 */
    @Test
    void shouldTimeOutWhenDeadlinePassed() {
        BrowserTakeoverRegistry expiredRegistry = createRegistry(0);

        BrowserTakeoverOutcome outcome = expiredRegistry.requestTakeover(page, TITLE, REASON, current -> true);

        assertEquals(BrowserTakeoverOutcome.TIMED_OUT, outcome);
        assertTrue(expiredRegistry.listTakeovers().isEmpty());
    }

    /** 窗口在看时才截图；按住左键后的移动带上左键，页面才会当成拖动；结束时停止截图。 */
    @Test
    void shouldStartScreencastForViewerAndDispatchDragWithLeftButton() {
        AtomicInteger checks = new AtomicInteger();
        doAnswer(invocation -> {
            if (checks.get() == 1) {
                BrowserTakeover takeover = registry.listTakeovers().get(0);
                takeover.readLatestFrame();
                takeover.addInput(new BrowserTakeoverInput(BrowserTakeoverInput.Type.MOUSE_PRESSED, 10, 20, 0, 0, null, null));
                takeover.addInput(new BrowserTakeoverInput(BrowserTakeoverInput.Type.MOUSE_MOVED, 60, 20, 0, 0, null, null));
            }
            return null;
        }).when(page).waitForTimeout(anyDouble());

        registry.requestTakeover(page, TITLE, REASON, current -> checks.incrementAndGet() >= 3);

        verify(cdpSession).send(eq("Page.startScreencast"), any(JsonObject.class));
        ArgumentCaptor<JsonObject> mouseEvents = ArgumentCaptor.forClass(JsonObject.class);
        verify(cdpSession, times(2)).send(eq("Input.dispatchMouseEvent"), mouseEvents.capture());
        JsonObject drag = mouseEvents.getAllValues().get(1);
        assertEquals("mouseMoved", drag.get("type").getAsString());
        assertEquals("left", drag.get("button").getAsString());
        assertEquals(1, drag.get("buttons").getAsInt());
        verify(cdpSession).send("Page.stopScreencast");
    }

    /** 已结束或不存在的接管按 404 语义拒绝。 */
    @Test
    void shouldRejectUnknownTakeover() {
        assertThrows(NoSuchElementException.class, () -> registry.getTakeover("missing"));
    }
}
