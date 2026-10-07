package org.huangry.colorful.geo.application.content;

import org.huangry.colorful.geo.domain.model.ContentOptimizationRecord;
import org.huangry.colorful.geo.domain.model.ContentPublishRecord;
import org.huangry.colorful.geo.domain.service.content.ContentOptimizationRecordService;
import org.huangry.colorful.geo.domain.service.content.ContentPublishRecordService;
import org.huangry.colorful.geo.domain.service.publish.ContentPublicationService;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.ContentPublishRecordStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内容发布业务测试；平台和数据库均使用替身，不产生真实草稿。
 */
class ContentPublishBusinessTest {

    private ContentPublicationService publicationService;
    private ContentOptimizationRecordService optimizationRecordService;
    private ContentPublishRecordService publishRecordService;
    private ContentPublishBusiness service;

    /** 每个用例使用独立的投放与持久化服务替身。 */
    @BeforeEach
    void setUp() {
        publicationService = mock(ContentPublicationService.class);
        optimizationRecordService = mock(ContentOptimizationRecordService.class);
        publishRecordService = mock(ContentPublishRecordService.class);
        service = new ContentPublishBusiness(publicationService, optimizationRecordService, publishRecordService);
    }

    /** 发布列表沿用原有分页语义，并只读取发布记录服务。 */
    @Test
    void 应分页读取发布记录() {
        ContentPublishRecord record = ContentPublishRecord.builder().id(11L).build();
        when(publishRecordService.listContentPublishRecords(20, 20L)).thenReturn(List.of(record));
        when(publishRecordService.countContentPublishRecords()).thenReturn(21L);

        var page = service.listContentPublishRecords(1, 20);

        assertEquals(List.of(record), page.items());
        assertEquals(21L, page.total());
        verify(publishRecordService).listContentPublishRecords(20, 20L);
    }

    /** 删除发布记录应委托本地持久化服务，不触碰平台接口。 */
    @Test
    void 应只删除本地发布记录() {
        service.deleteContentPublishRecord(11L);

        verify(publishRecordService).deleteContentPublishRecord(11L);
        verify(publicationService, never()).prePublishContent(any());
    }

    /** 用户确认后仅为新平台创建发布记录，并使用确认稿调用平台。 */
    @Test
    void 应只预发布新占用的平台任务() {
        ContentOptimizationRecord record = ContentOptimizationRecord.builder().id(7L).title("原标题")
                .optimizedContent("优化稿").build();
        when(optimizationRecordService.findById(7L)).thenReturn(Optional.of(record));
        when(publishRecordService.createInitializedRecord(7L, PublicationPlatformType.ZHIHU,
                "最终标题", "最终稿")).thenReturn(true);
        when(publishRecordService.createInitializedRecord(7L, PublicationPlatformType.JUEJIN,
                "最终标题", "最终稿")).thenReturn(false);
        PublicationResult result = PublicationResult.builder().platformType(PublicationPlatformType.ZHIHU)
                .status(PublicationTaskStatus.DRAFT_CREATED).remoteContentId("123").build();
        when(publicationService.prePublishContent(any())).thenReturn(List.of(result));
        when(publishRecordService.listByContentOptimizationRecordId(7L)).thenReturn(List.of(
                ContentPublishRecord.builder().contentOptimizationRecordId(7L).platformType(PublicationPlatformType.ZHIHU)
                        .publishStatus(ContentPublishRecordStatus.PRE_PUBLISHED).remoteContentId("123").build()));

        List<ContentPublishRecord> records = service.prePublish(7L, "最终标题", "最终稿", List.of(
                PublicationPlatformType.ZHIHU, PublicationPlatformType.ZHIHU, PublicationPlatformType.JUEJIN));

        ArgumentCaptor<PublicationRequest> request = ArgumentCaptor.forClass(PublicationRequest.class);
        verify(publicationService).prePublishContent(request.capture());
        assertEquals(List.of(PublicationPlatformType.ZHIHU), request.getValue().getPlatformTypes());
        assertEquals("最终标题", request.getValue().getTitle());
        assertEquals("最终稿", request.getValue().getContent());
        verify(publishRecordService).startPrePublish(7L, PublicationPlatformType.ZHIHU);
        verify(publishRecordService).savePrePublishResult(7L, result);
        assertEquals(7L, records.get(0).getContentOptimizationRecordId());
        assertEquals("123", records.get(0).getRemoteContentId());
    }

    /** 用户没有提交有效确认稿时，不能创建发布记录或调用平台。 */
    @Test
    void 空白确认稿不得预发布() {
        when(optimizationRecordService.findById(7L)).thenReturn(Optional.of(ContentOptimizationRecord.builder().id(7L)
                .optimizedContent("优化稿").build()));

        assertThrows(IllegalArgumentException.class,
                () -> service.prePublish(7L, "标题", " ", List.of(PublicationPlatformType.ZHIHU)));

        verify(publishRecordService, never()).createInitializedRecord(eq(7L), any(), any(), any());
        verify(publicationService, never()).prePublishContent(any());
    }

    /** 本地状态未进入预发布中时，不能先调用外部平台。 */
    @Test
    void 状态迁移失败时不调用平台() {
        when(optimizationRecordService.findById(7L)).thenReturn(Optional.of(ContentOptimizationRecord.builder().id(7L).build()));
        when(publishRecordService.createInitializedRecord(7L, PublicationPlatformType.ZHIHU,
                "标题", "正文")).thenReturn(true);
        doThrow(new IllegalStateException("状态迁移失败")).when(publishRecordService)
                .startPrePublish(7L, PublicationPlatformType.ZHIHU);

        assertThrows(IllegalStateException.class, () -> service.prePublish(7L,
                "标题", "正文", List.of(PublicationPlatformType.ZHIHU)));

        verify(publicationService, never()).prePublishContent(any());
    }
}
