package org.huangry.colorful.geo.infrastructure.client.login;

import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;

import com.alibaba.fastjson.JSON;
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
        PlatformScanLoginClient qrClient = new PlatformScanLoginClient();

        assertTrue(PlatformScanLoginClient.supportsPlatform(PublicationPlatformType.ZHIHU));
        assertTrue(qrClient.handlesScanMethod(PublicationPlatformType.ZHIHU, PlatformScanLoginMethod.PLATFORM_APP));
        assertFalse(qrClient.handlesScanMethod(PublicationPlatformType.ZHIHU, PlatformScanLoginMethod.WECHAT));
        assertFalse(qrClient.handlesScanMethod(PublicationPlatformType.ZHIHU, PlatformScanLoginMethod.QQ));
        assertTrue(PlatformAccountLoginClient.supportsSmsLogin(PublicationPlatformType.ZHIHU));
        assertTrue(PlatformAccountLoginClient.supportsPasswordLogin(PublicationPlatformType.ZHIHU));
        assertEquals(java.util.List.of(PlatformScanLoginMethod.WECHAT, PlatformScanLoginMethod.QQ),
                PlatformThirdPartyScanLoginClient.listScanMethods(PublicationPlatformType.ZHIHU));
    }

    @Test
    void zhihuAccountRequiresIdAndName() throws Exception {

        var browser = new PlaywrightBrowserComponent();
        var qrClient = new PlatformScanLoginClient();
        var formClient = new PlatformAccountLoginClient();
        var missingId = JSON.parseObject("{\"name\":\"见微\"}");
        var validAccount = JSON.parseObject("{\"id\":\"123\",\"name\":\"见微\"}");

        assertEquals(PlatformBrowserLoginStatus.UNKNOWN,
                qrClient.parseAccountResponse(PublicationPlatformType.ZHIHU, missingId).status());
        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN,
                qrClient.parseAccountResponse(PublicationPlatformType.ZHIHU, validAccount).status());
        assertEquals(PlatformBrowserLoginStatus.UNKNOWN, formClient.parseZhihuAccount(missingId).status());
        assertEquals(PlatformBrowserLoginStatus.LOGGED_IN, formClient.parseZhihuAccount(validAccount).status());
    }
}
