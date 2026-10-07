package org.huangry.colorful.geo.infrastructure.client.publish.strategy;

import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 验证草稿准备与已有草稿发布使用唯一的平台策略。
 *
 * <p>仅使用模拟策略，不调用任何外部平台。</p>
 *
 * @author huangry
 */
class PublicationPlatformStrategyRouterTest {

    /** 唯一匹配的平台策略负责创建草稿。 */
    @Test
    void 唯一匹配时应创建草稿() {
        PublicationPlatformStrategy draft = mock(PublicationPlatformStrategy.class);
        when(draft.platformType()).thenReturn(PublicationPlatformType.ZHIHU);
        when(draft.prePublish(any())).thenReturn(PublicationResult.builder()
                .status(PublicationTaskStatus.DRAFT_CREATED).build());
        PublicationPlatformStrategyRouter router = new PublicationPlatformStrategyRouter(List.of(draft));

        assertEquals(PublicationTaskStatus.DRAFT_CREATED,
                router.prePublish(buildRequest()).getStatus());
    }

    /** 未接入平台时明确拒绝，不尝试其他平台。 */
    @Test
    void 未接入平台时应明确拒绝() {
        PublicationPlatformStrategyRouter router = new PublicationPlatformStrategyRouter(List.of());

        PublicationClientException exception = assertThrows(PublicationClientException.class,
                () -> router.prePublish(buildRequest()));

        assertEquals("未接入目标平台的投放策略: ZHIHU", exception.getMessage());
    }

    /** 同一平台多个策略命中时拒绝，防止重复创建草稿。 */
    @Test
    void 同一平台匹配多个草稿策略时应拒绝() {
        PublicationPlatformStrategy first = mock(PublicationPlatformStrategy.class);
        PublicationPlatformStrategy second = mock(PublicationPlatformStrategy.class);
        when(first.platformType()).thenReturn(PublicationPlatformType.ZHIHU);
        when(second.platformType()).thenReturn(PublicationPlatformType.ZHIHU);
        PublicationPlatformStrategyRouter router = new PublicationPlatformStrategyRouter(List.of(first, second));

        PublicationClientException exception = assertThrows(PublicationClientException.class,
                () -> router.prePublish(buildRequest()));

        assertEquals("目标平台匹配到多个投放策略: ZHIHU", exception.getMessage());
        verify(first, never()).prePublish(any());
        verify(second, never()).prePublish(any());
    }

    /** 已有草稿发布只调用同一策略的 publish，不重新创建草稿。 */
    @Test
    void 草稿准备和已有草稿发布应命中草稿策略() {
        PublicationPlatformStrategy draft = mock(PublicationPlatformStrategy.class);
        PlatformPublicationRequest content = buildRequest();
        PrePublicationRequest existing = PrePublicationRequest.builder()
                .platformType(PublicationPlatformType.ZHIHU).remoteContentId("123").build();
        when(draft.platformType()).thenReturn(PublicationPlatformType.ZHIHU);
        when(draft.prePublish(content)).thenReturn(PublicationResult.builder()
                .status(PublicationTaskStatus.DRAFT_CREATED).build());
        when(draft.publish(existing)).thenReturn(PublicationResult.builder()
                .status(PublicationTaskStatus.PUBLISHED).build());
        PublicationPlatformStrategyRouter router = new PublicationPlatformStrategyRouter(List.of(draft));

        assertEquals(PublicationTaskStatus.DRAFT_CREATED, router.prePublish(content).getStatus());
        assertEquals(PublicationTaskStatus.PUBLISHED, router.publish(existing).getStatus());
        verify(draft).publish(existing);
    }

    /** 构造路由验证用的最小单平台内容请求。 */
    private PlatformPublicationRequest buildRequest() {
        return PlatformPublicationRequest.builder()
                .platformType(PublicationPlatformType.ZHIHU)
                .title("GEO 内容优化")
                .content("这是已经审核通过的最终发布稿。")
                .build();
    }
}
