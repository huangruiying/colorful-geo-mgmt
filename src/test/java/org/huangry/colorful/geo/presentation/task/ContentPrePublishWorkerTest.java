package org.huangry.colorful.geo.presentation.task;

import org.huangry.colorful.geo.domain.service.content.ContentPublishRecordService;
import org.huangry.colorful.geo.domain.service.publish.ContentPublicationService;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.repository.entity.ContentPublishRecordEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 验证预发布后台任务逐条领取、用确认稿调用平台、回写结果，以及单条失败不影响后续记录。 */
class ContentPrePublishWorkerTest {

    private ContentPublishRecordService publishRecordService;
    private ContentPublicationService publicationService;
    private ContentPrePublishWorker worker;

    /** 每个用例使用独立的持久化与平台调用替身，不启动轮询线程。 */
    @BeforeEach
    void setUp() {
        publishRecordService = mock(ContentPublishRecordService.class);
        publicationService = mock(ContentPublicationService.class);
        worker = new ContentPrePublishWorker();
        ReflectionTestUtils.setField(worker, "publishRecordService", publishRecordService);
        ReflectionTestUtils.setField(worker, "publicationService", publicationService);
    }

    /** 一轮处理完全部待预发布记录：每条用自己的确认稿调用对应平台，并回写平台结果。 */
    @Test
    void 逐条领取并回写平台结果() {
        ContentPublishRecordEntity zhihu = record(1L, 7L, PublicationPlatformType.ZHIHU);
        ContentPublishRecordEntity sohu = record(2L, 8L, PublicationPlatformType.SOHU);
        when(publishRecordService.claimNextInitializedRecord()).thenReturn(zhihu, sohu, null);
        PublicationResult zhihuResult = result(PublicationPlatformType.ZHIHU);
        PublicationResult sohuResult = result(PublicationPlatformType.SOHU);
        when(publicationService.prePublishContent(any())).thenReturn(List.of(zhihuResult), List.of(sohuResult));

        worker.tick();

        ArgumentCaptor<PublicationRequest> requests = ArgumentCaptor.forClass(PublicationRequest.class);
        verify(publicationService, times(2)).prePublishContent(requests.capture());
        assertEquals(List.of(PublicationPlatformType.ZHIHU), requests.getAllValues().get(0).getPlatformTypes());
        assertEquals("标题1", requests.getAllValues().get(0).getTitle());
        assertEquals("正文1", requests.getAllValues().get(0).getContent());
        assertEquals(List.of(PublicationPlatformType.SOHU), requests.getAllValues().get(1).getPlatformTypes());
        verify(publishRecordService).savePrePublishResult(7L, zhihuResult);
        verify(publishRecordService).savePrePublishResult(8L, sohuResult);
    }

    /** 一条记录回写失败时只记日志，记录留在预发布中待核对，后续记录照常处理。 */
    @Test
    void 单条回写失败不影响后续记录() {
        ContentPublishRecordEntity zhihu = record(1L, 7L, PublicationPlatformType.ZHIHU);
        ContentPublishRecordEntity sohu = record(2L, 8L, PublicationPlatformType.SOHU);
        when(publishRecordService.claimNextInitializedRecord()).thenReturn(zhihu, sohu, null);
        PublicationResult zhihuResult = result(PublicationPlatformType.ZHIHU);
        PublicationResult sohuResult = result(PublicationPlatformType.SOHU);
        when(publicationService.prePublishContent(any())).thenReturn(List.of(zhihuResult), List.of(sohuResult));
        doThrow(new IllegalStateException("回写失败")).when(publishRecordService).savePrePublishResult(7L, zhihuResult);

        worker.tick();

        verify(publishRecordService).savePrePublishResult(eq(8L), eq(sohuResult));
    }

    /** 领取失败时没有调用任何平台，并在等待期内不再领取。 */
    @Test
    void 领取失败时不调用平台并暂停领取() {
        when(publishRecordService.claimNextInitializedRecord()).thenThrow(new IllegalStateException("数据库不可用"));

        worker.tick();
        worker.tick();

        verify(publishRecordService, times(1)).claimNextInitializedRecord();
        verifyNoInteractions(publicationService);
    }

    /**
     * 构造已领取的发布记录，标题和正文按 ID 区分。
     *
     * @param id 发布记录 ID
     * @param articleId 文章（内容优化记录）ID
     * @param platform 目标平台
     * @return 发布记录实体
     */
    private ContentPublishRecordEntity record(long id, long articleId, PublicationPlatformType platform) {
        ContentPublishRecordEntity record = new ContentPublishRecordEntity();
        record.setId(id);
        record.setContentOptimizationRecordId(articleId);
        record.setPlatformType(platform);
        record.setPublicationTitle("标题" + id);
        record.setPublicationContent("正文" + id);
        return record;
    }

    /**
     * 构造平台已创建草稿的结果。
     *
     * @param platform 目标平台
     * @return 平台结果
     */
    private PublicationResult result(PublicationPlatformType platform) {
        return PublicationResult.builder().platformType(platform).status(PublicationTaskStatus.DRAFT_CREATED)
                .remoteContentId("1").build();
    }
}
