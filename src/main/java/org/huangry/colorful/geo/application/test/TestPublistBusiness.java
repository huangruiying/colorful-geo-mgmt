package org.huangry.colorful.geo.application.test;

import jakarta.annotation.Resource;

import java.util.List;

import org.huangry.colorful.geo.domain.service.publish.ContentPublicationService;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.springframework.stereotype.Component;

/**
 * 测试应用入口，仅演示内容草稿准备，不触发公开发布。
 *
 * @author huangry
 * Created in 2026/9/15 18:44
 */
@Component
public class TestPublistBusiness {

	@Resource
	private ContentPublicationService contentPublicationService;

	/**
	 * 保留原有知乎联调入口，仅创建草稿，不公开发布。
	 *
	 * @param title 测试标题
	 * @param text 测试正文
	 * @return 知乎草稿创建结果
	 */
	public List<PublicationResult> testPublish(String title, String text) {
		return testPrePublish(title, text, List.of(PublicationPlatformType.ZHIHU));
	}

	/**
	 * 将同一份测试内容提交给指定平台创建草稿，逐个平台返回结果。
	 *
	 * @param title 测试标题
	 * @param text 测试正文
	 * @param platformTypes 目标平台列表，单平台和多平台入口共用
	 * @return 各平台独立草稿结果，不代表已经公开发布
	 */
	public List<PublicationResult> testPrePublish(String title, String text,
			List<PublicationPlatformType> platformTypes) {
		// 将测试入口的目标平台交给既有批量服务统一校验、去重并串行创建草稿。
		PublicationRequest request = PublicationRequest.builder()
				.title(title)
				.platformTypes(platformTypes)
				.content(text)
				.build();
		return contentPublicationService.prePublishContent(request);
	}

}
