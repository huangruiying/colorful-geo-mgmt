package org.huangry.colorful.geo.domain.service.publish;

import org.huangry.colorful.geo.domain.service.content.ContentPublishRecordService;
import org.huangry.colorful.geo.infrastructure.client.publish.browser.weixin.WeixinBrowserClient;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.repository.entity.ContentPublishRecordEntity;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证查看只使用发布表中的平台 ID，不创建草稿、不更新状态。 */
class ContentDraftPreviewServiceTest {
    private final ContentPublishRecordService records = mock(ContentPublishRecordService.class);
    private final WeixinBrowserClient client = mock(WeixinBrowserClient.class);
    private final ContentDraftPreviewService service = new ContentDraftPreviewService(records, client);

    /** 返回平台实际内容，而不是本地确认稿快照。 */
    @Test
    void 按发布记录读取平台当前草稿() {
        var record = new ContentPublishRecordEntity();
        record.setPlatformType(PublicationPlatformType.WECHAT_OFFICIAL_ACCOUNT);
        record.setRemoteContentId("100000008");
        record.setPublicationTitle("本地旧标题");
        when(records.getById(48L)).thenReturn(record);
        when(client.readDraft("100000008")).thenReturn(new WeixinBrowserClient.DraftSnapshot("平台当前标题", "平台正文", "cG5n"));

        var result = service.readDraft(48L);

        assertEquals(48L, result.getPublishRecordId());
        assertEquals("平台当前标题", result.getTitle());
        assertEquals("平台正文", result.getContent());
        verify(records).getById(48L);
        verify(client).readDraft("100000008");
        verifyNoMoreInteractions(client, records);
    }

    /** 缺失记录不允许启动平台浏览器。 */
    @Test
    void 不存在的记录返回明确错误() {
        assertThrows(NoSuchElementException.class, () -> service.readDraft(1L));
        assertThrows(IllegalArgumentException.class, () -> service.readDraft(0L));
        verifyNoInteractions(client);
    }

    /** 不接受其他平台或没有草稿 ID 的记录，避免打开错误目标。 */
    @Test
    void 其他平台和缺失草稿ID均不得打开() {
        var record = new ContentPublishRecordEntity();
        record.setPlatformType(PublicationPlatformType.ZHIHU);
        when(records.getById(1L)).thenReturn(record);
        assertThrows(IllegalArgumentException.class, () -> service.readDraft(1L));
        record.setPlatformType(PublicationPlatformType.WECHAT_OFFICIAL_ACCOUNT);
        assertThrows(IllegalArgumentException.class, () -> service.readDraft(1L));
        record.setRemoteContentId("https://example.com/");
        assertThrows(IllegalArgumentException.class, () -> service.readDraft(1L));
        verifyNoInteractions(client);
    }
}
