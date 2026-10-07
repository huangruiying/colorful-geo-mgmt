package org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser;

import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.AbstractWechatsyncPrePublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.bilibili.BilibiliPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.dayu.DayuPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.douban.DoubanPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.douyin.DouyinPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.eastmoney.EastmoneyPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.imooc.ImoocPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.netease.NeteasePublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.oschina.OschinaPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.segmentfault.SegmentfaultPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.smzdm.SmzdmPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.sohu.SohuPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.sohufocus.SohufocusPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.woshipm.WoshipmPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.xiaohongshu.XiaohongshuPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.xueqiu.XueqiuPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.yidian.YidianPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.yuque.YuquePublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.cto51.Cto51PublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.browser.cnblogs.CnblogsPublicationStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformPublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PrePublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.PublicationPlatformStrategy;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.PublicationPlatformStrategyRouter;
import org.huangry.colorful.geo.infrastructure.client.wechatsync.WechatsyncClient;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationOutcomeUnknownException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 新增 Wechatsync 平台策略的路由与草稿结果测试。
 *
 * <p>仅模拟共享 CLI，不连接浏览器扩展、不创建真实草稿或公开发布。</p>
 *
 * <p>注意：百家号、掘金、今日头条、微博已迁出自管浏览器客户端（见各自
 * XxxBrowserClient / XxxPublicationStrategy），不再计入 Wechatsync 策略，故此处清单与计数
 * 同步减少 4 个。</p>
 *
 * @author huangry
 */
class WechatsyncPrePublicationStrategyTest {

    /** 单平台明确返回草稿标记时，路由应创建草稿且不编造远程标识。 */
    @Test
    void 全部新增平台应路由到各自的Wechatsync标识() {
        WechatsyncClient client = mock(WechatsyncClient.class);
        List<PlatformCase> cases = platformCases(client);
        PublicationPlatformStrategyRouter router = new PublicationPlatformStrategyRouter(
                cases.stream().map(PlatformCase::strategy).toList());
        assertEquals(19, cases.size());

        for (PlatformCase platformCase : cases) {
            String platform = platformCase.wechatsyncPlatform();
            when(client.sync(platform, "测试标题", "测试正文"))
                    .thenReturn("同步结果:\n✓ " + platform + " (草稿)\n同步完成: 1 成功, 0 失败\n");
            PlatformPublicationRequest request = PlatformPublicationRequest.builder()
                    .platformType(platformCase.platformType()).title("测试标题").content("测试正文").build();

            PublicationResult result = router.prePublish(request);

            assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus(), platform);
            assertEquals(platformCase.platformType(), result.getPlatformType(), platform);
            assertNull(result.getRemoteContentId(), platform);
            assertNull(result.getPublishedUrl(), platform);
            verify(client).sync(platform, "测试标题", "测试正文");
        }
    }

    /** 草稿成功行后的链接可返回；未返回的草稿标识保持为空。 */
    @Test
    void 草稿链接应按CLI结果返回() {
        AbstractWechatsyncPrePublicationStrategy strategy = new YuquePublicationStrategy(mock(WechatsyncClient.class));
        PublicationResult result = strategy.parseDraftResult(
                "同步结果:\n\u001B[32m✓\u001B[0m yuque (草稿)\nhttps://example.com/draft/123\n"
                        + "同步完成: 1 成功, 0 失败\n", "yuque");

        assertEquals(PublicationTaskStatus.DRAFT_CREATED, result.getStatus());
        assertEquals("https://example.com/draft/123", result.getDraftUrl());
        assertNull(result.getRemoteContentId());
    }

    /** 非草稿结果及其他平台成功均不得被误认为本平台草稿。 */
    @Test
    void 非草稿或失败结果应拒绝() {
        AbstractWechatsyncPrePublicationStrategy strategy = new YuquePublicationStrategy(mock(WechatsyncClient.class));
        List<String> invalidOutputs = List.of(
                "同步结果:\n✓ yuque\n同步完成: 1 成功, 0 失败\n",
                "同步结果:\n✓ zhihu (草稿)\n同步完成: 1 成功, 0 失败\n",
                "✓ yuque (草稿)\n同步完成: 1 成功, 0 失败\n");

        for (String output : invalidOutputs) {
            assertThrows(PublicationOutcomeUnknownException.class,
                    () -> strategy.parseDraftResult(output, "yuque"));
        }
    }

    /** CLI 明确返回单平台失败时记录确定失败，允许用户重新预发布。 */
    @Test
    void 明确同步失败可进入重试状态() {
        AbstractWechatsyncPrePublicationStrategy strategy = new YuquePublicationStrategy(mock(WechatsyncClient.class));

        PublicationClientException exception = assertThrows(PublicationClientException.class,
                () -> strategy.parseDraftResult("同步结果:\n✗ yuque\n同步完成: 0 成功, 1 失败\n", "yuque"));

        assertEquals(PublicationClientException.class, exception.getClass());
    }

    /** 模板先拦截错误平台，避免将请求正文提交给不对应的 Wechatsync 账号。 */
    @Test
    void 非本平台内容不应提交给Wechatsync() {
        WechatsyncClient client = mock(WechatsyncClient.class);
        YuquePublicationStrategy strategy = new YuquePublicationStrategy(client);
        PlatformPublicationRequest request = PlatformPublicationRequest.builder()
                .platformType(PublicationPlatformType.CSDN).title("测试标题").content("测试正文").build();

        assertThrows(PublicationClientException.class, () -> strategy.prePublish(request));
        verifyNoInteractions(client);
    }

    /** 新平台的公开发布尚未接入，不允许返回 PUBLISHED 或再次同步正文。 */
    @Test
    void 已有草稿公开发布应明确拒绝() {
        WechatsyncClient client = mock(WechatsyncClient.class);
        YuquePublicationStrategy strategy = new YuquePublicationStrategy(client);
        PrePublicationRequest request = PrePublicationRequest.builder()
                .platformType(PublicationPlatformType.YUQUE).remoteContentId("draft-123").build();

        PublicationClientException exception = assertThrows(PublicationClientException.class,
                () -> strategy.publish(request));

        assertEquals("语雀草稿公开发布暂未接入", exception.getMessage());
        verifyNoInteractions(client);
    }

    /** 测试用平台清单与各策略绑定的 CLI 标识一一对应。 */
    private List<PlatformCase> platformCases(WechatsyncClient client) {
        return List.of(
                new PlatformCase(new BilibiliPublicationStrategy(client), PublicationPlatformType.BILIBILI, "bilibili"),
                new PlatformCase(new YuquePublicationStrategy(client), PublicationPlatformType.YUQUE, "yuque"),
                new PlatformCase(new DoubanPublicationStrategy(client), PublicationPlatformType.DOUBAN, "douban"),
                new PlatformCase(new SohuPublicationStrategy(client), PublicationPlatformType.SOHU, "sohu"),
                new PlatformCase(new XueqiuPublicationStrategy(client), PublicationPlatformType.XUEQIU, "xueqiu"),
                new PlatformCase(new WoshipmPublicationStrategy(client), PublicationPlatformType.WOSHIPM, "woshipm"),
                new PlatformCase(new Cto51PublicationStrategy(client), PublicationPlatformType.CTO_51, "51cto"),
                new PlatformCase(new ImoocPublicationStrategy(client), PublicationPlatformType.IMOOC, "imooc"),
                new PlatformCase(new OschinaPublicationStrategy(client), PublicationPlatformType.OSCHINA, "oschina"),
                new PlatformCase(new SegmentfaultPublicationStrategy(client), PublicationPlatformType.SEGMENTFAULT, "segmentfault"),
                new PlatformCase(new CnblogsPublicationStrategy(client), PublicationPlatformType.CNBLOGS, "cnblogs"),
                new PlatformCase(new EastmoneyPublicationStrategy(client), PublicationPlatformType.EASTMONEY, "eastmoney"),
                new PlatformCase(new DayuPublicationStrategy(client), PublicationPlatformType.DAYU, "dayu"),
                new PlatformCase(new DouyinPublicationStrategy(client), PublicationPlatformType.DOUYIN, "douyin"),
                new PlatformCase(new NeteasePublicationStrategy(client), PublicationPlatformType.NETEASE, "netease"),
                new PlatformCase(new SmzdmPublicationStrategy(client), PublicationPlatformType.SMZDM, "smzdm"),
                new PlatformCase(new SohufocusPublicationStrategy(client), PublicationPlatformType.SOHU_FOCUS, "sohufocus"),
                new PlatformCase(new XiaohongshuPublicationStrategy(client), PublicationPlatformType.XIAOHONGSHU, "xiaohongshu"),
                new PlatformCase(new YidianPublicationStrategy(client), PublicationPlatformType.YIDIAN, "yidian")
        );
    }

    /** 测试中同时声明目标枚举与 CLI 平台标识，防止错误路由。 */
    private record PlatformCase(PublicationPlatformStrategy strategy,
                                PublicationPlatformType platformType, String wechatsyncPlatform) {
    }
}
