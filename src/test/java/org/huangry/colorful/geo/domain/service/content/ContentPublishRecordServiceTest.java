package org.huangry.colorful.geo.domain.service.content;

import org.huangry.colorful.geo.infrastructure.common.enums.ContentPublishRecordStatus;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.repository.ContentPublishRecordDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.ContentPublishRecordEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 平台发布记录持久化服务测试；DAO 约束状态迁移，不访问平台或本地数据库。
 */
class ContentPublishRecordServiceTest {

    private ContentPublishRecordDao dao;
    private ContentPublishRecordService service;

    /** 为每个测试建立独立的 DAO 替身。 */
    @BeforeEach
    void setUp() {
        dao = mock(ContentPublishRecordDao.class);
        service = new ContentPublishRecordService();
        ReflectionTestUtils.setField(service, "baseMapper", dao);
    }

    /** 非执行中记录由数据库条件删除，成功后不再读取记录状态。 */
    @Test
    void 删除非执行中发布记录() {
        when(dao.deleteNonProcessingRecord(11L)).thenReturn(1);

        service.deleteContentPublishRecord(11L);

        verify(dao).deleteNonProcessingRecord(11L);
    }

    /** 执行中记录删除未命中时，应向页面明确说明而不是误报删除成功。 */
    @Test
    void 拒绝删除执行中发布记录() {
        ContentPublishRecordEntity record = new ContentPublishRecordEntity();
        record.setId(11L);
        record.setPublishStatus(ContentPublishRecordStatus.PRE_PUBLISHING);
        when(dao.selectById(11L)).thenReturn(record);

        assertThrows(IllegalStateException.class, () -> service.deleteContentPublishRecord(11L));
    }

    /** 只有首次写入确认稿才能触发平台调用，重复选择应返回 false。 */
    @Test
    void 仅首次创建发布记录返回成功() {
        when(dao.createInitializedRecord(7L, PublicationPlatformType.ZHIHU,
                "标题", "正文")).thenReturn(1).thenThrow(new DuplicateKeyException("已存在"));

        assertTrue(service.createInitializedRecord(7L, PublicationPlatformType.ZHIHU, "标题", "正文"));
        assertFalse(service.createInitializedRecord(7L, PublicationPlatformType.ZHIHU, "标题", "正文"));
    }

    /** 重试明确失败的平台时，必须先删除旧记录，再保存本次确认稿。 */
    @Test
    void 预发布失败重试应先删除旧记录再初始化() {
        when(dao.deleteFailedPrePublishRecord(7L, PublicationPlatformType.ZHIHU)).thenReturn(1);
        when(dao.createInitializedRecord(7L, PublicationPlatformType.ZHIHU,
                "新标题", "新正文")).thenReturn(1);

        assertTrue(service.createInitializedRecord(7L, PublicationPlatformType.ZHIHU, "新标题", "新正文"));

        var calls = inOrder(dao);
        calls.verify(dao).deleteFailedPrePublishRecord(7L, PublicationPlatformType.ZHIHU);
        calls.verify(dao).createInitializedRecord(7L, PublicationPlatformType.ZHIHU, "新标题", "新正文");
    }

    /** 初始化记录必须成功进入预发布中，才能调用外部平台。 */
    @Test
    void 初始化后推进预发布状态() {
        when(dao.startPrePublish(7L, PublicationPlatformType.ZHIHU)).thenReturn(1);

        service.startPrePublish(7L, PublicationPlatformType.ZHIHU);

        verify(dao).startPrePublish(7L, PublicationPlatformType.ZHIHU);
    }

    /** 平台结果回写应使用实际返回的远程 ID，不能伪造内容标识。 */
    @Test
    void 保存平台实际返回的草稿信息() {
        PublicationResult result = PublicationResult.builder()
                .platformType(PublicationPlatformType.ZHIHU)
                .status(PublicationTaskStatus.DRAFT_CREATED)
                .remoteContentId("draft-123")
                .draftUrl("https://example.com/draft-123")
                .build();
        when(dao.savePrePublishResult(7L, PublicationPlatformType.ZHIHU,
                ContentPublishRecordStatus.PRE_PUBLISHED.getCode(), "draft-123", "https://example.com/draft-123",
                null, null)).thenReturn(1);

        service.savePrePublishResult(7L, result);

        verify(dao).savePrePublishResult(7L, PublicationPlatformType.ZHIHU,
                ContentPublishRecordStatus.PRE_PUBLISHED.getCode(), "draft-123", "https://example.com/draft-123",
                null, null);
    }

    /** 预发布结果不确定时仍停留在预发布中，并保存核对原因。 */
    @Test
    void 结果不确定时保持预发布中() {
        PublicationResult result = PublicationResult.builder()
                .platformType(PublicationPlatformType.ZHIHU)
                .status(PublicationTaskStatus.PUBLISHING)
                .message("请核对草稿箱").build();
        when(dao.savePrePublishResult(7L, PublicationPlatformType.ZHIHU,
                ContentPublishRecordStatus.PRE_PUBLISHING.getCode(), null, null,
                null, "请核对草稿箱")).thenReturn(1);

        service.savePrePublishResult(7L, result);

        verify(dao).savePrePublishResult(7L, PublicationPlatformType.ZHIHU,
                ContentPublishRecordStatus.PRE_PUBLISHING.getCode(), null, null,
                null, "请核对草稿箱");
    }

    /** 平台明确失败时记录预发布失败，而不是复用实际发布失败状态。 */
    @Test
    void 明确失败时写入预发布失败() {
        PublicationResult result = PublicationResult.builder()
                .platformType(PublicationPlatformType.ZHIHU)
                .status(PublicationTaskStatus.FAILED)
                .message("草稿创建失败").build();
        when(dao.savePrePublishResult(7L, PublicationPlatformType.ZHIHU,
                ContentPublishRecordStatus.PRE_PUBLISH_FAILED.getCode(), null, null,
                null, "草稿创建失败")).thenReturn(1);

        service.savePrePublishResult(7L, result);

        verify(dao).savePrePublishResult(7L, PublicationPlatformType.ZHIHU,
                ContentPublishRecordStatus.PRE_PUBLISH_FAILED.getCode(), null, null,
                null, "草稿创建失败");
    }
}
