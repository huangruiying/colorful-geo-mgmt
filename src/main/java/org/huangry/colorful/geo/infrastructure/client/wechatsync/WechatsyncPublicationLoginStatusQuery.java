package org.huangry.colorful.geo.infrastructure.client.wechatsync;

import lombok.RequiredArgsConstructor;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 将项目发布平台映射到 Wechatsync 平台标识；入口一次查询全部登录态，不负责登录和发布。
 */
@Component
@RequiredArgsConstructor
public class WechatsyncPublicationLoginStatusQuery {

	/** 与现有 Wechatsync 草稿策略使用的 CLI 平台标识保持一致。 */
	private static final Map<PublicationPlatformType, String> PLATFORM_IDS = Map.ofEntries(
			Map.entry(PublicationPlatformType.WECHAT_OFFICIAL_ACCOUNT, "weixin"),
			Map.entry(PublicationPlatformType.ZHIHU, "zhihu"),
			Map.entry(PublicationPlatformType.JUEJIN, "juejin"),
			Map.entry(PublicationPlatformType.WEIBO, "weibo"),
			Map.entry(PublicationPlatformType.CSDN, "csdn"),
			Map.entry(PublicationPlatformType.YUQUE, "yuque"),
			Map.entry(PublicationPlatformType.DOUBAN, "douban"),
			Map.entry(PublicationPlatformType.SOHU, "sohu"),
			Map.entry(PublicationPlatformType.XUEQIU, "xueqiu"),
			Map.entry(PublicationPlatformType.WOSHIPM, "woshipm"),
			Map.entry(PublicationPlatformType.CTO_51, "51cto"),
			Map.entry(PublicationPlatformType.IMOOC, "imooc"),
			Map.entry(PublicationPlatformType.OSCHINA, "oschina"),
			Map.entry(PublicationPlatformType.SEGMENTFAULT, "segmentfault"),
			Map.entry(PublicationPlatformType.CNBLOGS, "cnblogs"),
			Map.entry(PublicationPlatformType.EASTMONEY, "eastmoney"),
			Map.entry(PublicationPlatformType.DAYU, "dayu"),
			Map.entry(PublicationPlatformType.JIANSHU, "jianshu"),
			Map.entry(PublicationPlatformType.NETEASE, "netease"),
			Map.entry(PublicationPlatformType.SMZDM, "smzdm"),
			Map.entry(PublicationPlatformType.SOHU_FOCUS, "sohufocus"),
			Map.entry(PublicationPlatformType.YIDIAN, "yidian"),
			Map.entry(PublicationPlatformType.XIAOHONGSHU, "xiaohongshu"),
			Map.entry(PublicationPlatformType.DOUYIN, "douyin"),
			Map.entry(PublicationPlatformType.BILIBILI, "bilibili"),
			Map.entry(PublicationPlatformType.TOUTIAO, "toutiao"),
            Map.entry(PublicationPlatformType.BAIJIAHAO, "baijiahao"));

	private final WechatsyncClient client;

	/**
	 * 批量查询当前桥接扩展中的平台登录状态。
	 *
	 * @return 项目平台标识与明确登录结论的映射；缺项由上层标记未知
	 */
	public Map<PublicationPlatformType, WechatsyncClient.WechatsyncLoginAccount> listPlatformLoginStatuses() {
		Map<String, WechatsyncClient.WechatsyncLoginAccount> accounts = client.listPlatformLoginStatuses();
		Map<PublicationPlatformType, WechatsyncClient.WechatsyncLoginAccount> statuses =
				new java.util.EnumMap<>(PublicationPlatformType.class);
		// 未接入 Wechatsync 的发布平台不参与本通道映射；CLI 未返回的平台保持缺项。
		PLATFORM_IDS.forEach((platformType, platformId) -> {
			WechatsyncClient.WechatsyncLoginAccount account = accounts.get(platformId);
			if (account != null) {
				statuses.put(platformType, account);
			}
		});
		return statuses;
	}
}
