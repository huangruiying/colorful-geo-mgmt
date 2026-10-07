package org.huangry.colorful.geo.infrastructure.client.login;

import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformBrowserLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PlatformScanLoginMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证知乎在通用扫码与表单客户端中的接入边界。 */
class PlatformLoginRoutingTest {

    @Test
    void zhihuAppIsNativeQrWhileSocialMethodsAreNot() {
        QrPlatformPlaywrightLoginClient qrClient = new QrPlatformPlaywrightLoginClient(
                new ObjectMapper(), new PlaywrightBrowserComponent());

        assertTrue(QrPlatformPlaywrightLoginClient.supportsPlatform(PublicationPlatformType.ZHIHU));
        assertTrue(qrClient.handlesScanMethod(PublicationPlatformType.ZHIHU, PlatformScanLoginMethod.PLATFORM_APP));
        assertFalse(qrClient.handlesScanMethod(PublicationPlatformType.ZHIHU, PlatformScanLoginMethod.WECHAT));
        assertFalse(qrClient.handlesScanMethod(PublicationPlatformType.ZHIHU, PlatformScanLoginMethod.QQ));
        assertTrue(FormPlatformPlaywrightLoginClient.supportsSmsLogin(PublicationPlatformType.ZHIHU));
        assertTrue(FormPlatformPlaywrightLoginClient.supportsPasswordLogin(PublicationPlatformType.ZHIHU));
        assertEquals(java.util.List.of(PlatformScanLoginMethod.WECHAT, PlatformScanLoginMethod.QQ),
                PlatformThirdPartyLoginClient.listScanMethods(PublicationPlatformType.ZHIHU));
    }

    @Test
    void zhihuAccountRequiresIdAndName() throws Exception {
        var mapper = new ObjectMapper();
        var browser = new PlaywrightBrowserComponent();
        var qrClient = new QrPlatformPlaywrightLoginClient(mapper, browser);
        var formClient = new FormPlatformPlaywrightLoginClient(mapper, browser);
        var missingId = mapper.readTree("{\"name\":\"见微\"}");
        var validAccount = mapper.readTree("{\"id\":\"123\",\"name\":\"见微\"}");

        assertEquals(PlatformBrowserLoginStatus.UNKNOWN,
                qrClient.parseAccountResponse(PublicationPlatformType.ZHIHU, missingId).status());
        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN,
                qrClient.parseAccountResponse(PublicationPlatformType.ZHIHU, validAccount).status());
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN, formClient.parseZhihuAccount(missingId).status());
        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, formClient.parseZhihuAccount(validAccount).status());
    }
}
