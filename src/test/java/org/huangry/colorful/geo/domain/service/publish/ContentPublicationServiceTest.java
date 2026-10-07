package org.huangry.colorful.geo.domain.service.publish;

import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.PublicationPlatformStrategyRouter;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证内容批量投放的去重、结果顺序和单平台失败隔离。
 * <p>仅模拟策略路由，不访问真实平台。</p>
 *
 * @author huangry
 */
class ContentPublicationServiceTest {

    /** 相同平台只执行一次，平台失败仍保留前后平台各自的结果。 */
    @Test
    void 多平台应按首次出现顺序执行并隔离失败() {
        PublicationPlatformStrategyRouter router = mock(PublicationPlatformStrategyRouter.class);
        when(router.prePublish(any(PlatformPublicationRequest.class))).thenAnswer(invocation -> {
            PlatformPublicationRequest single = invocation.getArgument(0);
            if (single.getPlatformType() == PublicationPlatformType.JUEJIN) {
                throw new PublicationClientException("未接入目标平台的投放策略");
            }
            return PublicationResult.builder().platformType(single.getPlatformType())
                    .status(PublicationTaskStatus.DRAFT_CREATED).build();
        });
        PublicationRequest request = PublicationRequest.builder()
                .platformType(PublicationPlatformType.JUEJIN)
                .platformType(PublicationPlatformType.ZHIHU)
                .platformType(PublicationPlatformType.ZHIHU)
                .title("同一份文章").content("正文").build();

        List<PublicationResult> results = new ContentPublicationService(router).prePublishContent(request);

        assertEquals(2, results.size());
        assertEquals(PublicationPlatformType.JUEJIN, results.get(0).getPlatformType());
        assertEquals(PublicationTaskStatus.FAILED, results.get(0).getStatus());
        assertEquals("未接入目标平台的投放策略", results.get(0).getMessage());
        assertEquals(PublicationPlatformType.ZHIHU, results.get(1).getPlatformType());
        assertEquals(PublicationTaskStatus.DRAFT_CREATED, results.get(1).getStatus());
        org.mockito.Mockito.verify(router, org.mockito.Mockito.times(2))
                .prePublish(any(PlatformPublicationRequest.class));
    }

    /** 平台列表错误应在任一平台被调用前拒绝整批请求。 */
    @Test
    void 空平台列表应在投放前拒绝() {
        PublicationPlatformStrategyRouter router = mock(PublicationPlatformStrategyRouter.class);
        PublicationRequest request = PublicationRequest.builder()
                .title("标题").content("正文").build();

        assertThrows(PublicationClientException.class,
                () -> new ContentPublicationService(router).prePublishContent(request));
        verifyNoInteractions(router);
    }

    /** 发布已有草稿仅调用草稿发布入口，不重新提交标题和正文。 */
    @Test
    void 已有草稿发布应只调用草稿路由() {
        PublicationPlatformStrategyRouter router = mock(PublicationPlatformStrategyRouter.class);
        PrePublicationRequest request = PrePublicationRequest.builder()
                .platformType(PublicationPlatformType.ZHIHU).remoteContentId("123").build();
        when(router.publish(request)).thenReturn(PublicationResult.builder()
                .platformType(PublicationPlatformType.ZHIHU).status(PublicationTaskStatus.PUBLISHED).build());

        PublicationResult result = new ContentPublicationService(router).publishDraft(request);

        assertEquals(PublicationTaskStatus.PUBLISHED, result.getStatus());
        org.mockito.Mockito.verify(router).publish(request);
        org.mockito.Mockito.verify(router, org.mockito.Mockito.never())
                .prePublish(any(PlatformPublicationRequest.class));
    }

    /** 点击后超时属于结果待核对，不能返回确定失败诱导自动重试。 */
    @Test
    void 发布结果不确定时应保留发布中状态() {
        PublicationPlatformStrategyRouter router = mock(PublicationPlatformStrategyRouter.class);
        PrePublicationRequest request = PrePublicationRequest.builder()
                .platformType(PublicationPlatformType.ZHIHU).remoteContentId("123").build();
        when(router.publish(request)).thenThrow(new PublicationOutcomeUnknownException(
                "请人工核对草稿", new IllegalStateException("timeout")));

        PublicationResult result = new ContentPublicationService(router).publishDraft(request);

        assertEquals(PublicationTaskStatus.PUBLISHING, result.getStatus());
        assertEquals("请人工核对草稿", result.getMessage());
    }

    /** 意外异常可能发生在正文提交后，不能记录成允许删除重试的确定失败。 */
    @Test
    void 未知预发布异常应保留待核对状态() {
        PublicationPlatformStrategyRouter router = mock(PublicationPlatformStrategyRouter.class);
        when(router.prePublish(any(PlatformPublicationRequest.class)))
                .thenThrow(new IllegalStateException("无法确认结果"));
        PublicationRequest request = PublicationRequest.builder()
                .platformType(PublicationPlatformType.ZHIHU).title("标题").content("正文").build();

        PublicationResult result = new ContentPublicationService(router).prePublishContent(request).get(0);

        assertEquals(PublicationTaskStatus.PUBLISHING, result.getStatus());
    }
}
