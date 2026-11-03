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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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

/**
 * 浏览器人工接管的登记、等待、操作回放与结局：页面与 CDP 通道用替身，不启动浏览器；完成条件由测试直接给出。
 * <p>时钟可拨动：页面替身每等待一次就把时间往后拨相应毫秒，回放时刻可精确断言。</p>
 */
class BrowserTakeoverRegistryTest {

    private static final String TITLE = "搜狐号 · 预发布";
    private static final String REASON = "搜狐号要求对本浏览器做短信安全验证";

    private final ManualClock clock = new ManualClock();
    private final BrowserTakeoverRegistry registry = createRegistry(600);
    private final Page page = mock(Page.class);
    private final CDPSession cdpSession = mock(CDPSession.class);
    /** 每次等待时调用，测试在这里模拟窗口里的用户；默认什么都不做。 */
    private Consumer<BrowserTakeover> duringWait = takeover -> { };

    /**
     * 构造登记表，设置等待期限并换上可拨动的时钟。
     *
     * @param timeoutSeconds 等待期限（秒）
     * @return 登记表
     */
    private BrowserTakeoverRegistry createRegistry(long timeoutSeconds) {
        BrowserTakeoverRegistry takeoverRegistry = new BrowserTakeoverRegistry();
        ReflectionTestUtils.setField(takeoverRegistry, "timeoutSeconds", timeoutSeconds);
        ReflectionTestUtils.setField(takeoverRegistry, "clock", clock);
        return takeoverRegistry;
    }

    /** 页面替身能打开 CDP 通道；每次等待都把时钟往后拨，并让测试模拟用户。 */
    @BeforeEach
    void stubPage() {
        BrowserContext context = mock(BrowserContext.class);
        when(page.context()).thenReturn(context);
        when(context.newCDPSession(page)).thenReturn(cdpSession);
        doAnswer(invocation -> {
            clock.advanceMillis(invocation.<Double>getArgument(0));
            duringWait.accept(registry.listTakeovers().get(0));
            return null;
        }).when(page).waitForTimeout(anyDouble());
    }

    /** 完成条件满足即结束；等待期间可在登记表查到调用方给出的标题和原因，结束后移除。 */
    @Test
    void shouldCompleteWhenCompletionConditionIsMet() {
        AtomicInteger checks = new AtomicInteger();
        AtomicReference<BrowserTakeover> listedDuringWait = new AtomicReference<>();
        duringWait = listedDuringWait::set;

        BrowserTakeoverOutcomeEnum outcome = registry.requestTakeover(page, TITLE, REASON,
                current -> checks.incrementAndGet() >= 2);

        assertEquals(BrowserTakeoverOutcomeEnum.COMPLETED, outcome);
        assertEquals(TITLE, listedDuringWait.get().getTitle());
        assertEquals(REASON, listedDuringWait.get().getReason());
        assertTrue(registry.listTakeovers().isEmpty());
        verify(cdpSession).detach();
    }

    /** 页面跳转中读不到时按未完成处理，下一轮再判断，不因此失败。 */
    @Test
    void shouldTreatUnreadablePageAsNotCompleted() {
        AtomicInteger checks = new AtomicInteger();

        BrowserTakeoverOutcomeEnum outcome = registry.requestTakeover(page, TITLE, REASON, current -> {
            if (checks.incrementAndGet() == 1) {
                throw new PlaywrightException("Execution context was destroyed");
            }
            return true;
        });

        assertEquals(BrowserTakeoverOutcomeEnum.COMPLETED, outcome);
        assertEquals(2, checks.get());
    }

    /** 操作按发生时间的原间隔回放：第一个点放入后推迟 100ms 执行，之后相邻两点的间隔与用户操作时一致。 */
    @Test
    void shouldReplayInputsAtOriginalIntervals() {
        List<Instant> dispatchedAt = new ArrayList<>();
        doAnswer(invocation -> {
            dispatchedAt.add(clock.instant());
            return null;
        }).when(cdpSession).send(eq("Input.dispatchMouseEvent"), any(JsonObject.class));
        AtomicReference<Instant> addedAt = new AtomicReference<>();
        duringWait = takeover -> {
            if (addedAt.get() == null) {
                addedAt.set(clock.instant());
                takeover.addInputs(List.of(mouseInput(BrowserTakeoverInput.Type.MOUSE_PRESSED, 1000),
                        mouseInput(BrowserTakeoverInput.Type.MOUSE_MOVED, 1016),
                        mouseInput(BrowserTakeoverInput.Type.MOUSE_MOVED, 1040),
                        mouseInput(BrowserTakeoverInput.Type.MOUSE_RELEASED, 1048)));
            }
        };

        registry.requestTakeover(page, TITLE, REASON, current -> dispatchedAt.size() == 4);

        assertEquals(100, Duration.between(addedAt.get(), dispatchedAt.get(0)).toMillis());
        assertEquals(List.of(16L, 24L, 8L), List.of(
                Duration.between(dispatchedAt.get(0), dispatchedAt.get(1)).toMillis(),
                Duration.between(dispatchedAt.get(1), dispatchedAt.get(2)).toMillis(),
                Duration.between(dispatchedAt.get(2), dispatchedAt.get(3)).toMillis()));
    }

    /** 用户放弃后结束等待：已排定、即将到点的操作也不再作用到页面。 */
    @Test
    void shouldStopWithoutDispatchingQueuedInputsWhenAbandoned() {
        AtomicReference<Instant> addedAt = new AtomicReference<>();
        duringWait = takeover -> {
            if (addedAt.get() == null) {
                addedAt.set(clock.instant());
                takeover.addInputs(List.of(new BrowserTakeoverInput(BrowserTakeoverInput.Type.INSERT_TEXT, 0.0,
                        0, 0, 0, 0, "123456", null)));
            } else if (!clock.instant().isBefore(addedAt.get().plusMillis(100))) {
                // 恰好等到这个操作该执行的时刻时放弃
                takeover.abandon();
            }
        };

        BrowserTakeoverOutcomeEnum outcome = registry.requestTakeover(page, TITLE, REASON, current -> false);

        assertEquals(BrowserTakeoverOutcomeEnum.ABANDONED, outcome);
        verify(cdpSession, never()).send(eq("Input.insertText"), any(JsonObject.class));
    }

    /** 等待期限已到时按超时结束，不再检查完成条件。 */
    @Test
    void shouldTimeOutWhenDeadlinePassed() {
        BrowserTakeoverRegistry expiredRegistry = createRegistry(0);

        BrowserTakeoverOutcomeEnum outcome = expiredRegistry.requestTakeover(page, TITLE, REASON, current -> true);

        assertEquals(BrowserTakeoverOutcomeEnum.TIMED_OUT, outcome);
        assertTrue(expiredRegistry.listTakeovers().isEmpty());
    }

    /** 窗口在看时才截图；按住左键后的移动带上左键，页面才会当成拖动；结束时停止截图。 */
    @Test
    void shouldStartScreencastForViewerAndDispatchDragWithLeftButton() {
        AtomicInteger checks = new AtomicInteger();
        AtomicBoolean dragged = new AtomicBoolean();
        duringWait = takeover -> {
            if (!dragged.getAndSet(true)) {
                takeover.readLatestFrame();
                takeover.addInputs(List.of(mouseInput(BrowserTakeoverInput.Type.MOUSE_PRESSED, 0),
                        mouseInput(BrowserTakeoverInput.Type.MOUSE_MOVED, 16)));
            }
        };

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

    /**
     * 鼠标操作，坐标固定，测试只关心类型和发生时间。
     *
     * @param type 操作类型
     * @param clientTimeMillis 窗口记录的发生时间
     * @return 用户操作
     */
    private static BrowserTakeoverInput mouseInput(BrowserTakeoverInput.Type type, double clientTimeMillis) {
        return new BrowserTakeoverInput(type, clientTimeMillis, 10, 20, 0, 0, null, null);
    }

    /** 可拨动的时钟：只在页面替身等待时前进。 */
    private static final class ManualClock extends Clock {

        private Instant now = Instant.parse("2026-11-01T00:00:00Z");

        /**
         * 把时间往后拨。
         *
         * @param millis 毫秒数
         */
        void advanceMillis(double millis) {
            now = now.plusNanos(Math.round(millis * 1_000_000));
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
