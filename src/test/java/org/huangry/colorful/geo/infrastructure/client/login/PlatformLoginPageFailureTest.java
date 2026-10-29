package org.huangry.colorful.geo.infrastructure.client.login;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 登录页打开失败时的统一提示与失败截图。 */
class PlatformLoginPageFailureTest {

    /** 截到页面时提示对照截图确认原因，并附上截图。 */
    @Test
    void shouldAskToCheckScreenshotWhenPageCaptured() {
        Page page = mock(Page.class);
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenReturn("page".getBytes(StandardCharsets.UTF_8));

        var failure = PlatformLoginPageFailure.toException(page, PublicationPlatformType.SMZDM, null);

        assertEquals("什么值得买登录页未能正常打开，请对照下方页面截图确认原因后重试", failure.getMessage());
        assertEquals(Base64.getEncoder().encodeToString("page".getBytes(StandardCharsets.UTF_8)),
                failure.getScreenshotBase64());
    }

    /** 截图本身失败时不影响报错，退回提示检查网络。 */
    @Test
    void shouldFallBackToNetworkHintWhenScreenshotFails() {
        Page page = mock(Page.class);
        when(page.screenshot(any(Page.ScreenshotOptions.class))).thenThrow(new PlaywrightException("closed"));

        var failure = PlatformLoginPageFailure.toException(page, PublicationPlatformType.SMZDM, null);

        assertEquals("什么值得买登录页打开失败，请检查网络后重试", failure.getMessage());
        assertNull(failure.getScreenshotBase64());
    }

    /** 浏览器未创建成功时没有页面，提示检查网络。 */
    @Test
    void shouldFallBackToNetworkHintWhenPageMissing() {
        var failure = PlatformLoginPageFailure.toException(null, PublicationPlatformType.ZHIHU, null);

        assertEquals("知乎登录页打开失败，请检查网络后重试", failure.getMessage());
        assertNull(failure.getScreenshotBase64());
    }
}
