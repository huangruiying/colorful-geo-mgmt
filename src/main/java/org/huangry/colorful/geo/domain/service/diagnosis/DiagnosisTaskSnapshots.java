package org.huangry.colorful.geo.domain.service.diagnosis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.parser.Feature;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.repository.entity.*;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.LLMStrategy;

/**
 * 生成和读取诊断任务的配置快照。
 * 创建诊断任务时，冻结相关的配置。
 * <p>
 * 创建任务时冻结项目、方案、问题及渠道配置，供历史展示和执行前的配置比对使用。
 * 显式选择快照字段以排除独立 API Key；地址去除认证、查询串和片段，完整地址仅保留摘要供变化比对。
 * 提供方案、问题、渠道快照的组装与 JSON 读取入口；持久化、模型调用和配置一致性判定由调用方负责。
 */
@Component
public class DiagnosisTaskSnapshots {
	@Resource
	private Map<String, LLMStrategy> strategies;

	/**
	 * 创建诊断任务时，冻结计划快照
	 * 冻结项目、方案及完整诊断结果配置，项目资料仅用于历史展示和后续分析。
	 *
	 * @param plan      来源方案
	 * @param project   项目资料
	 * @param questions 已校验问题
	 * @param channels  已校验渠道
	 * @return 不含认证信息的完整快照
	 */
	public JSONObject plan(GeoDiagnosisPlanEntity plan, GeoProjectInfoEntity project,
	                       List<GeoDiagnosisPlanQuestionEntity> questions, List<GeoLlmChannelEntity> channels) {
		// 1. 固定快照版本与方案诊断次数，保留本轮任务创建时的方案信息
		JSONObject root = new JSONObject(true);
		root.fluentPut("version", 1).fluentPut("planId", plan.getId()).fluentPut("planName", plan.getName())
				.fluentPut("repeatCount", plan.getRepeatCount());
		// 2. 冻结项目资料供历史展示和后续分析，不在这里拼入模型问题
		JSONObject info = new JSONObject(true);
		root.put("project", info);
		info.fluentPut("id", project.getId()).fluentPut("name", project.getName()).fluentPut("websiteUrl", safeUrl(project.getWebsiteUrl()))
				.fluentPut("industry", project.getIndustry()).fluentPut("coreFeatures", project.getCoreFeatures())
				.fluentPut("targetAudience", project.getTargetAudience()).fluentPut("targetMarket", project.getTargetMarket())
				.fluentPut("targetLanguage", project.getTargetLanguage()).fluentPut("sellingPoints", project.getSellingPoints())
				.fluentPut("pricing", project.getPricing());
		info.put("aliases", JSON.toJSON(project.getAliases()));
		info.put("competitors", JSON.toJSON(project.getCompetitors()));
		info.put("keywords", JSON.toJSON(project.getKeywords()));
		// 3. 分别冻结问题和渠道，沿用各自的字段选择及地址处理规则
		var questionArray = new JSONArray();
		root.put("questions", questionArray);
		questions.forEach(question -> questionArray.add(question(question)));
		var channelArray = new JSONArray();
		root.put("channels", channelArray);
		channels.forEach(channel -> channelArray.add(channel(channel)));
		return root;
	}

	/**
	 * 创建诊断任务时，冻结问题快照
	 * 保存问题原文及分类，不把项目背景拼入被测问题。
	 *
	 * @param question 来源问题
	 * @return 单题快照
	 */
	public JSONObject question(GeoDiagnosisPlanQuestionEntity question) {
		JSONObject node = new JSONObject(true);
		node.fluentPut("id", question.getId()).fluentPut("sortOrder", question.getSortOrder()).fluentPut("text", question.getQuestionText())
				.fluentPut("source", question.getSource()).fluentPut("intent", question.getIntent())
				.fluentPut("brandScope", question.getBrandScope()).fluentPut("language", question.getLanguage());
		return node;
	}

	/**
	 * 创建诊断任务时，冻结模型及参数快照
	 * 冻结渠道标识、模型、生成参数及策略声明的协议；参数是否发送由具体策略决定。
	 * 地址展示移除认证、查询串和片段，完整地址摘要用于执行前比对。
	 *
	 * @param channel 渠道实体，包含的密钥不参与序列化或摘要
	 * @return 渠道快照及地址摘要
	 * @throws IllegalArgumentException 渠道指定的策略 Bean 未注册，无法确定调用协议
	 */
	public JSONObject channel(GeoLlmChannelEntity channel) {
		// 1. 显式选择渠道字段，独立 API Key 不进入快照；完整地址以摘要保留变化线索
		JSONObject node = new JSONObject(true);
		node.fluentPut("id", channel.getId()).fluentPut("channelCode", channel.getChannelCode())
				.fluentPut("channelName", channel.getChannelName()).fluentPut("providerCode", channel.getProviderCode())
				.fluentPut("strategyBeanName", channel.getStrategyBeanName()).fluentPut("modelName", channel.getModelName())
				.fluentPut("baseUrl", safeUrl(channel.getBaseUrl())).fluentPut("baseUrlDigest", digest(channel.getBaseUrl()));
		// 2. 从实际注册的策略确定协议，缺失时拒绝生成快照，避免凭渠道资料猜测协议
		LLMStrategy strategy = strategies.get(channel.getStrategyBeanName());
		if (strategy == null) {
			throw new IllegalArgumentException("渠道配置的策略 Bean 未注册");
		}
		node.put("protocolType", strategy.protocolType().name());
		// 3. 保留生成参数原值及空值，快照不补默认值或决定厂商是否支持这些参数
		node.put("temperature", channel.getTemperature());
		node.put("maxTokens", channel.getMaxTokens());
		return node;
	}

	/**
	 * 读取持久化 JSON；仅解析 JSON，不校验快照版本、字段结构或业务完整性。
	 *
	 * @param value JSON 字符串
	 * @return JSON 对象、数组或标量；JSON 字面量 null 返回 Java null
	 * @throws IllegalStateException 输入为空或 JSON 格式损坏
	 */
	public Object read(String value) {
		// 空输入不是有效的历史快照；JSON 字面量 null 则保留原值语义
		if (value == null || value.isBlank()) {
			throw new IllegalStateException("诊断快照为空");
		}
		try {
			// 解析原始快照，语法错误明确失败，避免用空对象掩盖持久化内容损坏
			// 小数沿用原快照的浮点比较语义，避免 BigDecimal 的小数位数参与相等判定
			return JSON.parse(value, JSON.DEFAULT_PARSER_FEATURE & ~ Feature.UseBigDecimal.mask);
		} catch (JSONException exception) {
			throw new IllegalStateException("诊断快照格式损坏", exception);
		}
	}

	/**
	 * 去除地址中的用户认证、查询串和片段，仅保留可展示部分；不检查可达性或路径中的敏感内容。
	 *
	 * @param value 原地址
	 * @return 仅含协议、主机、端口和路径的地址；未填写返回 null，解析失败或无主机返回无效地址占位
	 */
	private String safeUrl(String value) {
		// 未填写与填写了无效地址分开表示，便于历史页面识别原配置状态
		if (value == null || value.isBlank()) {
			return null;
		}
		// 只重建展示需要的地址部分；无法解析时保留占位，不把原始输入带回页面
		try {
			URI uri = URI.create(value);
			if (uri.getHost() == null) {
				return "[无效地址]";
			}
			return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString();
		} catch (IllegalArgumentException | java.net.URISyntaxException exception) {
			return "[无效地址]";
		}
	}

	/**
	 * 对完整地址计算摘要，供调用方发现展示值未体现的认证、查询串或片段变化。
	 *
	 * @param value 完整地址，不包括独立 API Key
	 * @return SHA-256 十六进制摘要；null 与空字符串使用同一摘要
	 * @throws IllegalStateException 运行环境不支持 SHA-256，无法生成用于配置比对的摘要
	 */
	private String digest(String value) {
		// 按完整原值计算，不先清洗地址；独立 API Key 不参与，允许换密钥时保持地址摘要不变
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(java.util.Objects.toString(value, "").getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("当前运行环境不支持配置摘要", exception);
		}
	}
}
