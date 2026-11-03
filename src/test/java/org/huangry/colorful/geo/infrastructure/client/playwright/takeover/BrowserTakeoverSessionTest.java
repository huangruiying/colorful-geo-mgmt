package org.huangry.colorful.geo.infrastructure.client.playwright.takeover;

import com.google.gson.JsonObject;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 操作回放的计时规则：网络晚到时重新计时、容差内照原时刻表追赶、时间不连续时重新对时。
 * <p>当前时刻由测试直接给出（毫秒），CDP 通道用替身；每段操作的第一个点推迟 100ms 执行。</p>
 */
class BrowserTakeoverSessionTest {

    private final Instant startedAt = Instant.parse("2026-11-01T00:00:00Z");
    private final BrowserTakeover takeover = new BrowserTakeover("id-1", "搜狐号 · 预发布", "原因",
            startedAt, startedAt.plusSeconds(600));
    private final CDPSession cdpSession = mock(CDPSession.class);
    private BrowserTakeoverSession session;

    /** 用页面替身打开接管通道。 */
    @BeforeEach
    void openSession() {
        Page page = mock(Page.class);
        BrowserContext context = mock(BrowserContext.class);
        when(page.context()).thenReturn(context);
        when(context.newCDPSession(page)).thenReturn(cdpSession);
        session = BrowserTakeoverSession.open(page, takeover);
    }

    /** 晚到超过 30ms 的点立即执行，并从它重新计时，后面的点保持原间隔，不挤成一串。 */
    @Test
    void shouldRestartTimingFromInputArrivingTooLate() {
        takeover.addInputs(List.of(move(0)));
        session.dispatchDueInputs(0);
        session.dispatchDueInputs(100);
        // 原定 150 执行的点到 300 才到
        takeover.addInputs(List.of(move(50), move(58)));

        double nextDispatchAt = session.dispatchDueInputs(300);

        assertEquals(308, nextDispatchAt);
        verify(cdpSession, times(2)).send(eq("Input.dispatchMouseEvent"), any(JsonObject.class));
    }

    /** 晚到 30ms 以内的点立即补上，后面的点仍按原时刻执行，等待本身的误差不会累积成延迟。 */
    @Test
    void shouldCatchUpSlightlyLateInputsOnOriginalSchedule() {
        takeover.addInputs(List.of(move(0), move(8), move(200)));
        session.dispatchDueInputs(0);

        double nextDispatchAt = session.dispatchDueInputs(120);

        assertEquals(300, nextDispatchAt);
        verify(cdpSession, times(2)).send(eq("Input.dispatchMouseEvent"), any(JsonObject.class));
    }

    /** 停顿超过 1 秒或换了窗口（时间起点不同）后，新的一段重新对时，第一个点同样推迟 100ms。 */
    @Test
    void shouldRestartTimingWhenClientTimeIsDiscontinuous() {
        takeover.addInputs(List.of(move(0)));
        session.dispatchDueInputs(0);
        session.dispatchDueInputs(100);

        takeover.addInputs(List.of(move(5000)));
        assertEquals(1100, session.dispatchDueInputs(1000));
        session.dispatchDueInputs(1100);

        takeover.addInputs(List.of(move(20)));
        assertEquals(2100, session.dispatchDueInputs(2000));
    }

    /**
     * 不按键的鼠标移动，测试只关心发生时间。
     *
     * @param clientTimeMillis 窗口记录的发生时间
     * @return 用户操作
     */
    private static BrowserTakeoverInput move(double clientTimeMillis) {
        return new BrowserTakeoverInput(BrowserTakeoverInput.Type.MOUSE_MOVED, clientTimeMillis, 10, 10, 0, 0, null, null);
    }
}
