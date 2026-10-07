package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.zhihu;

import org.huangry.colorful.geo.infrastructure.client.publish.browser.zhihu.ZhihuBrowserClient;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.PublicationPlatformStrategyRouter;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 知乎策略边界测试：预发布委托数据库会话浏览器创建草稿，公开发布仍调用同一浏览器客户端。
 * 浏览器与数据库均使用替身，不创建真实平台内容。
 */
class ZhihuPublicationStrategyTest {

    /** 只有草稿客户端确认创建后，策略才返回草稿 ID 与编辑链接。 */
    @Test
    void 预发布应返回已确认的知乎草稿() {
        ZhihuBrowserClient browserClient = mock(ZhihuBrowserClient.class);
        PlatformPublicationRequest request = request();
        when(browserClient.createDraft(request.getTitle(), request.getContent()))
                .thenReturn(new ZhihuBrowserClient.DraftCreated("2089813671291764793",
                        "https://zhuanlan.zhihu.com/p/2089813671291764793/edit"));

        var result = new ZhihuPublicationStrategy(browserClient).prePublish(request);

        assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus());
        assertEquals("2089813671291764793", result.getRemoteContentId());
        assertEquals("https://zhuanlan.zhihu.com/p/2089813671291764793/edit", result.getDraftUrl());
        assertNull(result.getPublishedUrl());
    }

    /** 浏览器无法确认草稿时保留待核对异常，策略不能误报创建成功。 */
    @Test
    void 草稿结果不确定时不返回成功() {
        ZhihuBrowserClient browserClient = mock(ZhihuBrowserClient.class);
        PlatformPublicationRequest request = request();
        when(browserClient.createDraft(request.getTitle(), request.getContent()))
                .thenThrow(new PublicationOutcomeUnknownException("请核对草稿箱"));

        assertThrows(PublicationOutcomeUnknownException.class,
                () -> new ZhihuPublicationStrategy(browserClient).prePublish(request));
    }

    /** 公共模板拒绝非知乎平台，避免目标平台错误时创建草稿。 */
    @Test
    void 非知乎内容不应创建草稿() {
        ZhihuBrowserClient browserClient = mock(ZhihuBrowserClient.class);
        PlatformPublicationRequest request = PlatformPublicationRequest.builder()
                .platformType(PublicationPlatformType.JUEJIN)
                .title("测试标题").content("测试正文").build();

        PublicationClientException exception = assertThrows(PublicationClientException.class,
                () -> new ZhihuPublicationStrategy(browserClient).prePublish(request));

        assertEquals("知乎策略仅支持 ZHIHU 平台", exception.getMessage());
        verifyNoInteractions(browserClient);
    }

    /** 路由补全平台标识，供发布记录回写到正确的平台行。 */
    @Test
    void 路由应返回知乎草稿的平台标识() {
        ZhihuBrowserClient browserClient = mock(ZhihuBrowserClient.class);
        PlatformPublicationRequest request = request();
        when(browserClient.createDraft(request.getTitle(), request.getContent()))
                .thenReturn(new ZhihuBrowserClient.DraftCreated("123456",
                        "https://zhuanlan.zhihu.com/p/123456/edit"));

        var result = new PublicationPlatformStrategyRouter(
                List.of(new ZhihuPublicationStrategy(browserClient)))
                .prePublish(request);

        assertEquals(PublicationPlatformType.ZHIHU, result.getPlatformType());
        verify(browserClient).createDraft(request.getTitle(), request.getContent());
    }

    /** 已有草稿公开发布仍走同一浏览器客户端，不重复创建草稿。 */
    @Test
    void 发布已有草稿应保留原有浏览器流程() {
        ZhihuBrowserClient browserClient = mock(ZhihuBrowserClient.class);
        PrePublicationRequest request = PrePublicationRequest.builder()
                .platformType(PublicationPlatformType.ZHIHU).remoteContentId("2089813671291764793").build();
        when(browserClient.publishDraft(request.getRemoteContentId()))
                .thenReturn("https://zhuanlan.zhihu.com/p/2089813671291764793");

        var result = new ZhihuPublicationStrategy(browserClient).publish(request);

        assertEquals(PublicationTaskStatus.PUBLISHED, result.getStatus());
        assertEquals("https://zhuanlan.zhihu.com/p/2089813671291764793", result.getPublishedUrl());
        assertNull(result.getDraftUrl());
    }

    /** 已有草稿标识无效时，仍由抽象模板先拒绝，不触达发布浏览器。 */
    @Test
    void 草稿标识为空时不调用浏览器() {
        ZhihuBrowserClient browserClient = mock(ZhihuBrowserClient.class);
        PrePublicationRequest request = PrePublicationRequest.builder()
                .platformType(PublicationPlatformType.ZHIHU).remoteContentId(" ").build();

        assertThrows(PublicationClientException.class,
                () -> new ZhihuPublicationStrategy(browserClient).publish(request));
        verifyNoInteractions(browserClient);
    }

    /** 非知乎草稿不能调用知乎公开发布客户端。 */
    @Test
    void 非知乎草稿不调用发布浏览器() {
        ZhihuBrowserClient browserClient = mock(ZhihuBrowserClient.class);
        PrePublicationRequest request = PrePublicationRequest.builder()
                .platformType(PublicationPlatformType.JUEJIN).remoteContentId("123").build();

        assertThrows(PublicationClientException.class,
                () -> new ZhihuPublicationStrategy(browserClient).publish(request));
        verifyNoInteractions(browserClient);
    }

    /** 构造最小合法的知乎草稿请求。 */
    private PlatformPublicationRequest request() {
        return PlatformPublicationRequest.builder()
                .platformType(PublicationPlatformType.ZHIHU)
                .title("GEO 测试草稿").content("## 正文标题\n\n测试正文。").build();
    }
}
