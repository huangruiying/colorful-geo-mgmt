package org.huangry.colorful.geo.application.test;

import org.huangry.colorful.geo.domain.service.publish.ContentPublicationService;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 测试应用层的批量草稿请求组装行为。
 *
 * <p>仅模拟领域服务，确认新旧入口都走预发布，不执行平台请求或公开发布。</p>
 *
 * @author huangry
 */
@ExtendWith(MockitoExtension.class)
class TestPublistBusinessTest {

    @Mock
    private ContentPublicationService contentPublicationService;

    @InjectMocks
    private TestPublistBusiness testPublistBusiness;

    /** 多个平台共享同一份标题和正文，由领域服务逐平台处理。 */
    @Test
    void 多平台预发布应组装统一内容请求() {
        List<PublicationPlatformType> platforms = List.of(PublicationPlatformType.JUEJIN, PublicationPlatformType.CSDN);
        List<PublicationResult> results = List.of();
        when(contentPublicationService.prePublishContent(any(PublicationRequest.class))).thenReturn(results);

        List<PublicationResult> actual = testPublistBusiness.testPrePublish("同一标题", "同一正文", platforms);

        ArgumentCaptor<PublicationRequest> request = ArgumentCaptor.forClass(PublicationRequest.class);
        verify(contentPublicationService).prePublishContent(request.capture());
        assertEquals(platforms, request.getValue().getPlatformTypes());
        assertEquals("同一标题", request.getValue().getTitle());
        assertEquals("同一正文", request.getValue().getContent());
        assertSame(results, actual);
    }

    /** 保留的知乎入口只创建知乎草稿，不转到公开发布动作。 */
    @Test
    void 原知乎测试入口仍应走预发布服务() {
        when(contentPublicationService.prePublishContent(any(PublicationRequest.class))).thenReturn(List.of());

        testPublistBusiness.testPublish("知乎标题", "知乎正文");

        ArgumentCaptor<PublicationRequest> request = ArgumentCaptor.forClass(PublicationRequest.class);
        verify(contentPublicationService).prePublishContent(request.capture());
        assertEquals(List.of(PublicationPlatformType.ZHIHU), request.getValue().getPlatformTypes());
    }
}
