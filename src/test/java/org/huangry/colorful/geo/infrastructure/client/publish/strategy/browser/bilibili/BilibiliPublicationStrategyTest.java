package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.bilibili;

import org.huangry.colorful.geo.infrastructure.client.publish.browser.bilibili.BilibiliBrowserClient;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 验证哔哩哔哩策略只在浏览器客户端确认草稿后返回预发布成功，且不开放公开发布。 */
class BilibiliPublicationStrategyTest {

    /** 已确认草稿的标识和编辑链接必须原样传给上层。 */
    @Test
    void 应返回浏览器确认的草稿标识与链接() {
        BilibiliBrowserClient client = mock(BilibiliBrowserClient.class);
        String remoteId = "https://member.bilibili.com/york/read-editor?aid=123".replaceFirst("^.*[=/]", "");
        when(client.createDraft("测试标题", "测试正文"))
                .thenReturn(new BilibiliBrowserClient.DraftCreated(remoteId, "https://member.bilibili.com/york/read-editor?aid=123"));
        BilibiliPublicationStrategy strategy = new BilibiliPublicationStrategy();
        ReflectionTestUtils.setField(strategy, "browserClient", client);

        PublicationResult result = strategy.prePublish(PlatformPublicationRequest.builder()
                .platformType(PublicationPlatformType.BILIBILI).title("测试标题").content("测试正文").build());

        assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus());
        assertEquals(remoteId, result.getRemoteContentId());
        assertEquals("https://member.bilibili.com/york/read-editor?aid=123", result.getDraftUrl());
        assertEquals("哔哩哔哩专栏草稿已创建，尚未公开发布", result.getMessage());
        verify(client).createDraft("测试标题", "测试正文");
    }

    /** 公开发布未接入，不能把草稿误标成已发布。 */
    @Test
    void 公开发布未接入时拒绝() {
        BilibiliPublicationStrategy strategy = new BilibiliPublicationStrategy();

        assertThrows(PublicationClientException.class, () -> strategy.publish(PrePublicationRequest.builder()
                .platformType(PublicationPlatformType.BILIBILI).remoteContentId("1").build()));
    }
}
