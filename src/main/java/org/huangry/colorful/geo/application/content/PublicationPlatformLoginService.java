package org.huangry.colorful.geo.application.content;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformLoginStatus;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationPlatformOption;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.PublicationPlatformStrategy;
import org.huangry.colorful.geo.infrastructure.client.wechatsync.WechatsyncClient;
import org.huangry.colorful.geo.infrastructure.client.wechatsync.WechatsyncPublicationLoginStatusQuery;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.PublicationClientException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 汇总已接入发布策略的平台登录状态；入口只读，不触发扫码、草稿创建或公开发布。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicationPlatformLoginService {

	private final List<PublicationPlatformStrategy> strategies;
	private final WechatsyncPublicationLoginStatusQuery wechatsyncStatusQuery;

	/**
	 * 列出已接入策略的平台，不等待浏览器扩展返回登录态。
	 *
	 * @return 按平台枚举顺序排列的页面选项
	 */
	public List<PublicationPlatformOption> listConfiguredPlatforms() {
		return strategies.stream().map(PublicationPlatformStrategy::platformType).distinct()
				.sorted(Comparator.comparingInt(Enum::ordinal))
				.map(platform -> new PublicationPlatformOption(platform, platform.getDisplayName())).toList();
	}

	/**
	 * 展示所有已接入发布策略的平台及其当前登录状态。
	 *
	 * @return 按平台枚举顺序排列的登录信息；通道不可用时状态为 UNKNOWN
	 */
	public List<PlatformLoginStatus> listPlatformLoginStatuses() {
		// 1. 仅展示实际注册了发布策略的平台，不把枚举中的规划平台误当作已接入。
		List<PublicationPlatformType> platforms = listConfiguredPlatforms().stream()
				.map(PublicationPlatformOption::platformType).toList();

		// 2. 同一通道只查询一次；桥接不可用时不把登录失败误报为未登录。
		Map<PublicationPlatformType, WechatsyncClient.WechatsyncLoginAccount> accounts;
		try {
			accounts = wechatsyncStatusQuery.listPlatformLoginStatuses();
		} catch (PublicationClientException exception) {
			log.warn("发布平台登录状态查询失败，受影响平台标记为 UNKNOWN，reason={}", exception.getMessage());
			accounts = Map.of();
		}

		// 3. 只将通道明确返回的结论映射为已登录或未登录，未返回则保留 UNKNOWN。
		Map<PublicationPlatformType, WechatsyncClient.WechatsyncLoginAccount> finalAccounts = accounts;
		return platforms.stream().map(platformType -> {
			WechatsyncClient.WechatsyncLoginAccount account = finalAccounts.get(platformType);
			PublicationLoginStatus status = account == null ? PublicationLoginStatus.UNKNOWN
					: account.loggedIn() ? PublicationLoginStatus.LOGGED_IN : PublicationLoginStatus.NOT_LOGGED_IN;
			return new PlatformLoginStatus(platformType, platformType.getDisplayName(), status,
					account != null && account.loggedIn() ? account.username() : null);
		}).toList();
	}
}
