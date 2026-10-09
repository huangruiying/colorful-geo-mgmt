package org.huangry.colorful.geo.application.content;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationPlatformOption;
import org.huangry.colorful.geo.infrastructure.client.publish.strategy.PublicationPlatformStrategy;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

/**
 * 汇总已接入发布策略的平台选项，供内容发布页选择目标平台。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PublicationPlatformLoginService {

	private final List<PublicationPlatformStrategy> strategies;

	/**
	 * 列出已接入策略的平台，供内容页面选择目标；不查询登录状态。
	 *
	 * @return 按平台枚举顺序排列的页面选项
	 */
	public List<PublicationPlatformOption> listConfiguredPlatforms() {
		return strategies.stream().map(PublicationPlatformStrategy::platformType).distinct()
				.sorted(Comparator.comparingInt(Enum::ordinal))
				.map(platform -> new PublicationPlatformOption(platform, platform.getDisplayName())).toList();
	}
}
