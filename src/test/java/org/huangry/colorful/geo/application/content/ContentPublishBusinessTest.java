package org.huangry.colorful.geo.application.content;

import org.huangry.colorful.geo.application.content.model.ContentPublishTitleOption;
import org.huangry.colorful.geo.infrastructure.common.model.PageQuery;
import org.huangry.colorful.geo.infrastructure.repository.entity.ContentPublishRecordEntity;
import org.huangry.colorful.geo.domain.model.ContentOptimizationRecord;
import org.huangry.colorful.geo.domain.model.ContentPublishRecord;
import org.huangry.colorful.geo.domain.service.content.ContentOptimizationRecordService;
import org.huangry.colorful.geo.domain.service.content.ContentPublishRecordService;
import org.huangry.colorful.geo.infrastructure.common.enums.ContentPublishRecordStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.PublicationPlatformStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationPlatformOption;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 内容发布业务测试；平台和数据库均使用替身，不产生真实草稿。
 */
class ContentPublishBusinessTest {

    private ContentOptimizationRecordService optimizationRecordService;
    private ContentPublishRecordService publishRecordService;
    private ContentPublishBusiness service;

    /** 每个用例使用独立的持久化服务替身。 */
    @BeforeEach
    void setUp() {
        optimizationRecordService = mock(ContentOptimizationRecordService.class);
        publishRecordService = mock(ContentPublishRecordService.class);
        service = new ContentPublishBusiness();
        ReflectionTestUtils.setField(service, "optimizationRecordService", optimizationRecordService);
        ReflectionTestUtils.setField(service, "publishRecordService", publishRecordService);
    }

    /** 只返回已接入平台；重复策略去重，注册顺序不改变页面顺序。 */
    @Test
    void 应按枚举顺序返回已接入平台() {
        var weibo = mock(PublicationPlatformStrategy.class);
        var zhihu = mock(PublicationPlatformStrategy.class);
        when(weibo.platformType()).thenReturn(PublicationPlatformType.WEIBO);
        when(zhihu.platformType()).thenReturn(PublicationPlatformType.ZHIHU);
        ReflectionTestUtils.setField(service, "strategies", List.of(weibo, zhihu, weibo));
        assertEquals(List.of(
            new PublicationPlatformOption(PublicationPlatformType.ZHIHU, "知乎"),
            new PublicationPlatformOption(PublicationPlatformType.WEIBO, "微博")), service.listConfiguredPlatforms());
    }

    /** 发布列表沿用原有分页语义；列表和总数使用同一个文章筛选条件。 */
    @Test
    void 应分页读取发布记录() {
        ContentPublishRecord record = ContentPublishRecord.builder().id(11L).build();
        when(publishRecordService.listContentPublishRecords(new PageQuery(1, 20), 7L)).thenReturn(List.of(record));
        when(publishRecordService.countContentPublishRecords(7L)).thenReturn(21L);

        var page = service.listContentPublishRecords(1, 20, 7L);

        assertEquals(List.of(record), page.items());
        assertEquals(21L, page.total());
        verify(publishRecordService).listContentPublishRecords(new PageQuery(1, 20), 7L);
        verify(publishRecordService).countContentPublishRecords(7L);
    }

    /** 文章标题选项带上全拼和首字母，供页面按拼音搜索。 */
    @Test
    void 文章标题选项附带拼音() {
        ContentPublishRecordEntity latest = new ContentPublishRecordEntity();
        latest.setContentOptimizationRecordId(7L);
        latest.setPublicationTitle("预发布测试 GEO");
        when(publishRecordService.listLatestRecordPerArticle()).thenReturn(List.of(latest));

        assertEquals(List.of(new ContentPublishTitleOption(7L, "预发布测试 GEO", "yufabuceshigeo", "yfbcsgeo")),
                service.listContentPublishTitles());
    }

    /** 删除发布记录应委托本地持久化服务，不触碰平台接口。 */
    @Test
    void 应只删除本地发布记录() {
        service.deleteContentPublishRecord(11L);

        verify(publishRecordService).deleteContentPublishRecord(11L);
    }

    /** 提交预发布只为每个平台建立待预发布记录（重复平台去重），不调用平台，草稿由后台任务创建。 */
    @Test
    void 提交预发布只建立待预发布记录() {
        when(optimizationRecordService.findById(7L)).thenReturn(Optional.of(ContentOptimizationRecord.builder().id(7L).build()));
        ContentPublishRecord initialized = ContentPublishRecord.builder().contentOptimizationRecordId(7L)
                .platformType(PublicationPlatformType.ZHIHU).publishStatus(ContentPublishRecordStatus.INITIALIZED).build();
        when(publishRecordService.listByContentOptimizationRecordId(7L)).thenReturn(List.of(initialized));

        List<ContentPublishRecord> records = service.prePublish(7L, " 最终标题 ", "最终稿", List.of(
                PublicationPlatformType.ZHIHU, PublicationPlatformType.ZHIHU, PublicationPlatformType.JUEJIN));

        verify(publishRecordService).createInitializedRecord(7L, PublicationPlatformType.ZHIHU, "最终标题", "最终稿");
        verify(publishRecordService).createInitializedRecord(7L, PublicationPlatformType.JUEJIN, "最终标题", "最终稿");
        assertEquals(List.of(initialized), records);
    }

    /** 用户没有提交有效确认稿时，不能创建发布记录或调用平台。 */
    @Test
    void 空白确认稿不得预发布() {
        when(optimizationRecordService.findById(7L)).thenReturn(Optional.of(ContentOptimizationRecord.builder().id(7L)
                .optimizedContent("优化稿").build()));

        assertThrows(IllegalArgumentException.class,
                () -> service.prePublish(7L, "标题", " ", List.of(PublicationPlatformType.ZHIHU)));

        verify(publishRecordService, never()).createInitializedRecord(eq(7L), any(), any(), any());
    }

}
