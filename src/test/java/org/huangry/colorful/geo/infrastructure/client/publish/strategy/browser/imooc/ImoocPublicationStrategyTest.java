package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.imooc;

import org.huangry.colorful.geo.infrastructure.client.publish.browser.imooc.ImoocBrowserClient;
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

/** 验证慕课网策略只在浏览器客户端确认草稿后返回预发布成功，且不开放公开发布。 */
class ImoocPublicationStrategyTest {

    /** 已确认草稿的标识和编辑链接必须原样传给上层。 */
    @Test
    void 应返回浏览器确认的草稿标识与链接() {
        ImoocBrowserClient client = mock(ImoocBrowserClient.class);
        String remoteId = "https://www.imooc.com/article/draft/id/123".replaceFirst("^.*[=/]", "");
        when(client.createDraft("测试标题", "测试正文"))
                .thenReturn(new ImoocBrowserClient.DraftCreated(remoteId, "https://www.imooc.com/article/draft/id/123"));
        ImoocPublicationStrategy strategy = new ImoocPublicationStrategy();
        ReflectionTestUtils.setField(strategy, "browserClient", client);

        PublicationResult result = strategy.prePublish(PlatformPublicationRequest.builder()
                .platformType(PublicationPlatformType.IMOOC).title("测试标题").content("测试正文").build());

        assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus());
        assertEquals(remoteId, result.getRemoteContentId());
        assertEquals("https://www.imooc.com/article/draft/id/123", result.getDraftUrl());
        assertEquals("慕课网草稿已创建，尚未公开发布", result.getMessage());
        verify(client).createDraft("测试标题", "测试正文");
    }

    /** 公开发布未接入，不能把草稿误标成已发布。 */
    @Test
    void 公开发布未接入时拒绝() {
        ImoocPublicationStrategy strategy = new ImoocPublicationStrategy();

        assertThrows(PublicationClientException.class, () -> strategy.publish(PrePublicationRequest.builder()
                .platformType(PublicationPlatformType.IMOOC).remoteContentId("1").build()));
    }
}
