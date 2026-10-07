package org.huangry.colorful.geo.infrastructure.client.playwright;

import org.huangry.colorful.geo.infrastructure.common.exceptions.PlatformBrowserLoginException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 验证浏览器任务异常只直传已知登录异常，普通运行时异常由调用方映射。 */
class BrowserTaskRunnerTest {

    /** 平台登录异常保留原始提示，不重复包装。 */
    @Test
    void shouldPassThroughPlatformLoginException() {
        BrowserTaskRunner runner = new BrowserTaskRunner("login-test");
        try {
            PlatformBrowserLoginException original = new PlatformBrowserLoginException("页面要求重新登录");

            PlatformBrowserLoginException actual = assertThrows(PlatformBrowserLoginException.class,
                    () -> runner.execute(() -> { throw original; },
                            cause -> new PlatformBrowserLoginException("浏览器操作失败", cause)));

            assertSame(original, actual);
        } finally {
            runner.shutdown(() -> { });
        }
    }

    /** Playwright 等普通运行时异常须映射为页面可读的登录异常。 */
    @Test
    void shouldMapUnexpectedRuntimeException() {
        BrowserTaskRunner runner = new BrowserTaskRunner("login-test");
        try {
            IllegalStateException original = new IllegalStateException("browser unavailable");

            PlatformBrowserLoginException actual = assertThrows(PlatformBrowserLoginException.class,
                    () -> runner.execute(() -> { throw original; },
                            cause -> new PlatformBrowserLoginException("浏览器操作失败", cause)));

            assertSame(original, actual.getCause());
        } finally {
            runner.shutdown(() -> { });
        }
    }
}
