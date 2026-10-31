package org.huangry.colorful.geo.infrastructure.client.publish.browser.douyin;

import org.huangry.colorful.geo.infrastructure.client.playwright.PlaywrightBrowserComponent;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.huangry.colorful.geo.infrastructure.repository.PlatformBrowserLoginDao;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 验证抖音文章标题上限、草稿 ID 读取与登录态边界。 */
class DouyinBrowserClientTest {

    /** 草稿 ID 取自“继续编辑”打开的地址中的 creation_id。 */
    @Test
    void 从草稿地址读取creationId() {
        assertEquals("onufykqd1793431499893", DouyinBrowserClient.readCreationId(
                "https://creator.douyin.com/creator-micro/content/post/article?enter_from=draft&creation_id=onufykqd1793431499893&init_timestamp=1793431500"));
    }

    /** 地址中没有 creation_id 时结果未确认。 */
    @Test
    void 缺少creationId时结果未确认() {
        assertThrows(PublicationOutcomeUnknownException.class, () -> DouyinBrowserClient.readCreationId(
                "https://creator.douyin.com/creator-micro/content/post/article?enter_from=draft"));
    }

    /** 草稿查询返回带 creation_id 的草稿即视为草稿位已被占用；没有草稿时可以写入。 */
    @Test
    void 按草稿查询结果判断草稿位() {
        assertTrue(DouyinBrowserClient.hasUnpublishedArticle(
                "{\"draft\":{\"creation_id\":\"onufykqd1793431499893\",\"title\":\"标题\"},\"status_code\":0}"));
        assertFalse(DouyinBrowserClient.hasUnpublishedArticle("{\"status_code\":0,\"status_msg\":\"success\"}"));
        assertFalse(DouyinBrowserClient.hasUnpublishedArticle("{\"draft\":{\"creation_id\":\"\"},\"status_code\":0}"));
    }

    /** 标题超过 30 字时在打开浏览器前拒绝，避免被输入框截断。 */
    @Test
    void 标题超过30字时拒绝() {
        PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
        PlatformBrowserLoginDao loginDao = mock(PlatformBrowserLoginDao.class);
        var client = new DouyinBrowserClient();
        ReflectionTestUtils.setField(client, "loginDao", loginDao);
        ReflectionTestUtils.setField(client, "browserComponent", browser);

        var exception = assertThrows(PublicationClientException.class,
                () -> client.createDraft("一二三四五六七八九十一二三四五六七八九十一二三四五六七八九十多", "正文"));

        assertEquals("抖音文章标题最多 30 字，当前 31 字，请缩短后重试", exception.getMessage());
        verifyNoInteractions(browser, loginDao);
    }

    /** 数据库没有登录记录时，不打开浏览器。 */
    @Test
    void 未登录时不打开浏览器() {
        PlaywrightBrowserComponent browser = mock(PlaywrightBrowserComponent.class);
        var client = new DouyinBrowserClient();
        ReflectionTestUtils.setField(client, "loginDao", mock(PlatformBrowserLoginDao.class));
        ReflectionTestUtils.setField(client, "browserComponent", browser);

        assertThrows(PublicationClientException.class, () -> client.createDraft("标题", "正文"));
        verifyNoInteractions(browser);
    }
}
