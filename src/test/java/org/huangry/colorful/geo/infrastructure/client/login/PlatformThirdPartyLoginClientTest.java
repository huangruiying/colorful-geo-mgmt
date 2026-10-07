package org.huangry.colorful.geo.infrastructure.client.login;

import org.huangry.colorful.geo.infrastructure.common.enums.PlatformScanLoginMethod;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 核对已实现的第三方登录按钮，不启动浏览器或访问第三方网站。 */
class PlatformThirdPartyLoginClientTest {

    /** 简书和博客园有微信及 QQ，只有原生扫码的平台不虚构第三方入口。 */
    @Test
    void 只展示已接入的微信和QQ登录方式() {
        assertEquals(List.of(PlatformScanLoginMethod.WECHAT, PlatformScanLoginMethod.QQ),
                PlatformThirdPartyLoginClient.listScanMethods(PublicationPlatformType.JIANSHU));
        assertEquals(List.of(PlatformScanLoginMethod.WECHAT, PlatformScanLoginMethod.QQ),
                PlatformThirdPartyLoginClient.listScanMethods(PublicationPlatformType.CNBLOGS));
        assertEquals(List.of(),
                PlatformThirdPartyLoginClient.listScanMethods(PublicationPlatformType.WECHAT_OFFICIAL_ACCOUNT));
        assertEquals(List.of(),
                PlatformThirdPartyLoginClient.listScanMethods(PublicationPlatformType.SEGMENTFAULT));
        assertEquals(List.of(PlatformScanLoginMethod.QQ),
                PlatformThirdPartyLoginClient.listScanMethods(PublicationPlatformType.SMZDM));
        assertEquals(List.of(PlatformScanLoginMethod.QQ),
                PlatformThirdPartyLoginClient.listScanMethods(PublicationPlatformType.CSDN));
        assertEquals(List.of(PlatformScanLoginMethod.WECHAT, PlatformScanLoginMethod.QQ),
                PlatformThirdPartyLoginClient.listScanMethods(PublicationPlatformType.SOHU));
        assertEquals(List.of(PlatformScanLoginMethod.WECHAT),
                PlatformThirdPartyLoginClient.listScanMethods(PublicationPlatformType.YUQUE));
        assertEquals(List.of(PlatformScanLoginMethod.WECHAT, PlatformScanLoginMethod.QQ),
                PlatformThirdPartyLoginClient.listScanMethods(PublicationPlatformType.EASTMONEY));
        assertEquals(List.of(PlatformScanLoginMethod.WECHAT, PlatformScanLoginMethod.QQ),
                PlatformThirdPartyLoginClient.listScanMethods(PublicationPlatformType.YIDIAN));
    }
}
