package org.huangry.colorful.geo.infrastructure.client.playwright.takeover.verification;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeoverOutcome;
import org.huangry.colorful.geo.infrastructure.client.playwright.takeover.BrowserTakeoverRegistry;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 人工验证场景：何时请求接管、窗口标题与原因、以验证消失作为完成条件；框架用替身。 */
class HumanVerificationTakeoverTest {

    private static final String SOHU_CLIENT_AUTH_URL = "https://mp.sohu.com/mpfe/v4/clientAuth?from=editor";
    private static final String SOHU_EDITOR_URL = "https://mp.sohu.com/mpfe/v4/contentManagement/news/addarticle";

    private final BrowserTakeoverRegistry takeoverRegistry = mock(BrowserTakeoverRegistry.class);
    private final HumanVerificationTakeover verificationTakeover = new HumanVerificationTakeover();
    private final Page page = mock(Page.class);

    /** 注入框架替身；腾讯验证码 iframe 默认不可见。 */
    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(verificationTakeover, "takeoverRegistry", takeoverRegistry);
        Locator locator = mock(Locator.class);
        when(page.locator(anyString())).thenReturn(locator);
        when(locator.first()).thenReturn(locator);
        when(locator.isVisible()).thenReturn(false);
    }

    /** 页面没有验证时直接返回，不发起接管。 */
    @Test
    void shouldReturnNotRequiredWithoutRequestingTakeover() {
        when(page.url()).thenReturn(SOHU_EDITOR_URL);

        BrowserTakeoverOutcome outcome = verificationTakeover.ensureHumanVerified(page, PublicationPlatformType.SOHU, "预发布");

        assertEquals(BrowserTakeoverOutcome.NOT_REQUIRED, outcome);
        verifyNoInteractions(takeoverRegistry);
    }

    /** 搜狐号专属规则只对搜狐号生效，其他平台停在同样地址不算待验证。 */
    @Test
    void shouldApplyPlatformRuleOnlyToItsPlatform() {
        when(page.url()).thenReturn(SOHU_CLIENT_AUTH_URL);

        assertEquals(HumanVerificationRule.SOHU_CLIENT_AUTH,
                HumanVerificationRule.detect(page, PublicationPlatformType.SOHU).orElseThrow());
        assertTrue(HumanVerificationRule.detect(page, PublicationPlatformType.ZHIHU).isEmpty());
    }

    /** 停在验证上时以“平台 · 业务”为标题、规则原因为说明请求接管；验证页消失才算完成。 */
    @Test
    @SuppressWarnings("unchecked")
    void shouldRequestTakeoverUntilVerificationDisappears() {
        when(page.url()).thenReturn(SOHU_CLIENT_AUTH_URL);
        ArgumentCaptor<Predicate<Page>> completionCondition = ArgumentCaptor.forClass(Predicate.class);
        when(takeoverRegistry.requestTakeover(eq(page), eq("搜狐号 · 预发布"), eq("搜狐号要求对本浏览器做短信安全验证"),
                completionCondition.capture())).thenReturn(BrowserTakeoverOutcome.COMPLETED);

        BrowserTakeoverOutcome outcome = verificationTakeover.ensureHumanVerified(page, PublicationPlatformType.SOHU, "预发布");

        assertEquals(BrowserTakeoverOutcome.COMPLETED, outcome);
        assertFalse(completionCondition.getValue().test(page));
        when(page.url()).thenReturn(SOHU_EDITOR_URL);
        assertTrue(completionCondition.getValue().test(page));
        verify(takeoverRegistry).requestTakeover(eq(page), anyString(), anyString(), any());
    }
}
