package org.huangry.colorful.geo.presentation.controller;

import jakarta.annotation.Resource;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.application.test.TestLLMBusiness;
import org.huangry.colorful.geo.application.test.TestOptimizeBusiness;
import org.huangry.colorful.geo.application.test.TestPublistBusiness;
import org.huangry.colorful.geo.domain.model.ContentOptimizationResult;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试接口入口，提供大模型、内容优化与平台草稿联调。
 *
 * <p>平台接口只触发预发布，不负责公开发布或持久化投放任务。</p>
 *
 * @author huangry
 * Created in 2026/9/15 18:44
 */
@RestController
@Slf4j
@RequestMapping("test")
public class TestController {

	@Resource
	private TestLLMBusiness testLLMBusiness;
	@Resource
	private TestOptimizeBusiness testOptimizeBusiness;
	@Resource
	private TestPublistBusiness testPublistBusiness;

	/**
	 * 调用测试大模型通道；日志只记录问题和回答长度，不输出原文。
	 *
	 * @param text 测试问题
	 * @return 大模型回答及引用
	 */
	@PostMapping("llmQuery")
	public LLMResponse llmQuery(@RequestParam("text") String text) {
		log.info("测试大模型查询 request questionLength={}", text.length());
		LLMResponse result = testLLMBusiness.llmQuery(text);
		log.info("测试大模型查询 response answerLength={} citationCount={}",
				result.content().length(), result.citations().size());
		return result;
	}

	/**
	 * 测试内容优化能力；日志仅记录输入输出长度。
	 *
	 * @param text 待优化正文
	 * @return 优化稿及策略信息
	 */
	@PostMapping("testOptimizeBusiness")
	public ContentOptimizationResult testOptimizeBusiness(@RequestParam("text") String text) {
		log.info("测试内容优化 request contentLength={}", text.length());
		ContentOptimizationResult result = testOptimizeBusiness.testOptimize(text);
		log.info("测试内容优化 response contentLength={} strategyCount={}",
				result.optimizedContent().length(), result.appliedStrategies().size());
		return result;
	}

	/**
	 * 联调批量预发布；按平台记录结果状态，不输出稿件正文。
	 *
	 * @param title 测试标题
	 * @param text 测试正文
	 * @return 各平台预发布结果，不代表所有平台均成功
	 */
	@PostMapping("testPublistBusiness")
	public List<PublicationResult> testPublistBusiness(
			@RequestParam("title") String title,
			@RequestParam("text") String text) {
		log.info("测试批量预发布 request titleLength={} contentLength={}", title.length(), text.length());
		List<PublicationResult> results = testPublistBusiness.testPublish(title, text);
		log.info("测试批量预发布 response results={}",
				results.stream().map(result -> result.getPlatformType() + ":" + result.getStatus()).toList());
		return results;
	}

	/**
	 * 按平台枚举逐一测试对应草稿策略，不执行公开发布。
	 *
	 * @param platformType 目标平台枚举名称，例如 JUEJIN
	 * @param title 测试标题
	 * @param text 测试正文
	 * @return 目标平台的预发布结果列表
	 */
	@PostMapping("prePublish/{platformType}")
	public List<PublicationResult> prePublishPlatform(
			@PathVariable("platformType") PublicationPlatformType platformType,
			@RequestParam("title") String title,
			@RequestParam("text") String text) {
		log.info("测试单平台预发布 request platform={} titleLength={} contentLength={}",
				platformType, title.length(), text.length());
		List<PublicationResult> results = testPublistBusiness.testPrePublish(title, text, List.of(platformType));
		log.info("测试单平台预发布 response platform={} results={}", platformType,
				results.stream().map(PublicationResult::getStatus).toList());
		return results;
	}

	/**
	 * 将同一份测试内容批量创建为多个平台的草稿，不执行公开发布。
	 *
	 * @param platformTypes 目标平台枚举列表，支持逗号分隔
	 * @param title 测试标题
	 * @param text 测试正文
	 * @return 按平台分别返回的预发布结果
	 */
	@PostMapping("prePublishBatch")
	public List<PublicationResult> prePublishBatch(
			@RequestParam("platformTypes") List<PublicationPlatformType> platformTypes,
			@RequestParam("title") String title,
			@RequestParam("text") String text) {
		log.info("测试指定平台预发布 request platforms={} titleLength={} contentLength={}",
				platformTypes, title.length(), text.length());
		List<PublicationResult> results = testPublistBusiness.testPrePublish(title, text, platformTypes);
		log.info("测试指定平台预发布 response results={}",
				results.stream().map(result -> result.getPlatformType() + ":" + result.getStatus()).toList());
		return results;
	}

}
