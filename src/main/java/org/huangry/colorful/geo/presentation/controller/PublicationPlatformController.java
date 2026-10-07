package org.huangry.colorful.geo.presentation.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.application.content.PublicationPlatformLoginService;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PlatformLoginStatus;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationPlatformOption;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 发布平台信息的只读接口；当前入口展示登录状态，不执行登录或内容发布。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/publication/platforms")
public class PublicationPlatformController {

	private final PublicationPlatformLoginService loginService;

	/**
	 * 快速列出有预发布策略的平台，供内容页面选择目标；不查询登录状态。
	 *
	 * @return 可选择的平台列表
	 */
	@GetMapping
	public List<PublicationPlatformOption> listConfiguredPlatforms() {
		log.info("查询可发布平台 request");
		List<PublicationPlatformOption> platforms = loginService.listConfiguredPlatforms();
		log.info("查询可发布平台 response platformCount={}", platforms.size());
		return platforms;
	}

	/**
	 * 查询已接入发布策略的平台登录状态，供发布页展示。
	 *
	 * @return 平台、登录结论和可选账号名
	 */
	@GetMapping("/login-status")
	public List<PlatformLoginStatus> listPlatformLoginStatuses() {
		log.info("查询发布平台登录状态 request");
		List<PlatformLoginStatus> statuses = loginService.listPlatformLoginStatuses();
		log.info("查询发布平台登录状态 response statuses={}",
				statuses.stream().map(status -> status.platformType() + ":" + status.status()).toList());
		return statuses;
	}
}
