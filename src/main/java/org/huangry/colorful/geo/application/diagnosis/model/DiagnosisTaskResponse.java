package org.huangry.colorful.geo.application.diagnosis.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.alibaba.fastjson.JSONObject;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 诊断任务创建、列表与详情接口的响应，名称和配置取自创建任务时的快照。
 * 只展示冻结的非敏感配置，不返回渠道实体或认证密钥；顶层空字段不输出到 JSON。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagnosisTaskResponse(
        /** 本轮诊断任务 ID，区别于来源方案 ID。 */
		Long id,
		/** 创建本轮任务的来源方案 ID。 */
		Long planId,
		/** 创建任务时冻结的方案名称，后续编辑方案不影响历史展示。 */
		String planName,
		/** 创建任务时关联的项目 ID。 */
		Long projectId,
		/** 创建任务时冻结的项目名称。 */
		String projectName,

		/** 任务状态：PENDING 待执行、RUNNING 执行中、SUCCEEDED 全部成功、PARTIAL_FAILED 部分失败、FAILED 失败。 */
		String status,
		/** 整轮任务的实时诊断结果统计。 */
		ProgressResponse progress,
		/** 按冻结渠道分别统计的诊断进度，列表与详情均返回。 */
		List<ChannelProgressResponse> channels,
		/** 项目、方案、问题与渠道的完整冻结配置；创建和详情返回，任务列表省略。 */
		JSONObject snapshot,

		/** 数据库记录的任务创建时间。 */
		LocalDateTime createdAt,
		/** 整轮任务开始执行的时间，尚未开始时为空。 */
		LocalDateTime startedAt,
		/** 整轮任务进入终态的时间，尚未结束时为空。 */
		LocalDateTime finishedAt
) {
	/**
	 * 从诊断结果实时聚合的数量统计，可用于整轮任务或单个渠道。
	 * 已结束数量为 succeeded + failed，总数为四种诊断结果状态数量之和。
	 */
	public record ProgressResponse(
			/** 当前统计范围内的诊断总次数，包含待执行与已结束的诊断。 */
			long total,
			/** 尚未开始执行的诊断次数。 */
			long pending,
			/** 正在执行且尚未保存最终结果的诊断次数。 */
			long running,
			/** 已成功保存有效回答的诊断结果数。 */
			long succeeded,
			/** 已记录失败结果的诊断结果数。 */
			long failed
	) {
	}

	/**
	 * 每个冻结渠道的进度，不使用当前渠道配置替换历史名称。
	 */
	public record ChannelProgressResponse(
			/** 本轮任务选定的渠道 ID。 */
			Long channelId,
			/** 创建任务时冻结的渠道名称。 */
			String channelName,
			/** 仅统计本渠道在当前任务中的诊断结果。 */
			ProgressResponse progress
	) {
	}

	/**
	 * 单次独立诊断的列表行或回答详情，单次诊断标识由任务、问题、渠道与重复序号确定。
	 * 列表只查询基础字段；回答、引用、模型元数据和起止时间由详情提供，空字段不输出到 JSON。
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ResultResponse(
			/** 单次诊断记录 ID，用于查询这一条回答详情。 */
			Long id,
			/** 所属诊断任务 ID。 */
			Long taskId,
			/** 创建任务时冻结的问题排序号。 */
			int questionSortOrder,
			/** 冻结的问题原文，不拼入项目背景或历史回答。 */
			String questionText,
			/** 问题原文、来源、意图、品牌范围和语言等冻结资料。 */
			JSONObject questionSnapshot,
			/** 本次诊断指定的渠道 ID。 */
			Long channelId,
			/** 创建任务时冻结的渠道名称。 */
			String channelName,
			/** 渠道、策略、模型及生成参数等冻结配置，不包含独立 API Key。 */
			JSONObject channelSnapshot,
			/** 同一问题、同一渠道的独立诊断序号，从 1 开始；不是重试次数。 */
			int repeatIndex,

			/** 诊断结果状态：PENDING 待执行、RUNNING 执行中、SUCCEEDED 成功、FAILED 失败。 */
			String status,
			/** 模型回答正文，仅详情返回；未成功时为空。 */
			String answerText,
			/** 结构化引用 JSON 数组，仅详情返回；成功但无引用时为 []，未成功时为空。 */
			Object citations,
			/** 厂商实际返回的模型标识，仅详情返回；未提供时为空，不以配置值补齐。 */
			String modelName,
			/** 厂商用量 JSON，可能包含 Token 和工具调用次数；仅详情返回，未提供时为空。 */
			Object usageInfo,
			/** 本次诊断处理耗时，单位毫秒；尚无处理结果时为空。 */
			Long durationMs,
			/** 厂商返回的结束原因或响应状态，仅详情返回；未提供时为空。 */
			String finishReason,
			/** 厂商响应的 JSON 字符串，仅详情返回；不在此对象中解析或脱敏。 */
			String rawResponse,
			/** 本地分类后的失败原因，不直接回传远程异常原文；未失败时为空。 */
			String errorMessage,

			/** 单次诊断开始时间，仅详情返回；尚未执行时为空。 */
			LocalDateTime startedAt,
			/** 单次诊断结束时间，仅详情返回；尚未结束时为空。 */
			LocalDateTime finishedAt
	) {
	}
}
