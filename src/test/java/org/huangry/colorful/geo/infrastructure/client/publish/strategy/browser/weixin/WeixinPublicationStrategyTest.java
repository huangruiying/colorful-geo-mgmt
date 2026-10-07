package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.weixin;

import org.huangry.colorful.geo.infrastructure.client.publish.browser.weixin.WeixinBrowserClient;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证公众号策略传递草稿 ID，但不泄露带会话 token 的编辑链接。 */
class WeixinPublicationStrategyTest {

    /** 平台回读确认后返回草稿状态，编辑地址保持为空。 */
    @Test
    void 应返回已确认草稿标识且不返回后台会话链接() {
        WeixinBrowserClient client = mock(WeixinBrowserClient.class);
        when(client.createDraft("测试标题", "测试正文")).thenReturn("100000005");
        WeixinPublicationStrategy strategy = new WeixinPublicationStrategy(client);

        PublicationResult result = strategy.prePublish(PlatformPublicationRequest.builder()
                .platformType(PublicationPlatformType.WECHAT_OFFICIAL_ACCOUNT)
                .title("测试标题").content("测试正文").build());

        assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus());
        assertEquals("100000005", result.getRemoteContentId());
        assertNull(result.getDraftUrl());
    }
}
