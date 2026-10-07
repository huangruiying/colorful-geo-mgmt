package org.huangry.colorful.geo.infrastructure.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * GEO 内容优化策略。
 *
 * <p>用于让领域服务按论文定义生成可控提示词，不承担事实核验或真实来源检索职责。</p>
 *
 * @author huangry
 */
@AllArgsConstructor
@Getter
public enum ContentOptimizationStrategy {

	/** 权威性优化：强化专业表达，不新增未经证实的权威背书。 */
	AUTHORITY("权威性优化", "使用清晰定义、稳定术语和审慎结论提升专业表达，不夸大原文结论。", false),
	/** 统计信息补充：使用参考资料中的数据，保留统计口径与来源。 */
	STATISTICS_ADDITION("统计信息补充", "仅使用参考资料中的统计数据，补充时间范围、统计口径和数据来源。", true),
	/** 引用来源：为关键事实补充已提供参考资料中的来源。 */
	CITE_SOURCES("引用来源", "仅引用已提供的参考资料，为关键事实补充可验证来源。", true),
	/** 引文增加：引用参考资料原文，注明作者或机构及来源。 */
	QUOTATION_ADDITION("引文增加", "仅使用参考资料中的原文引述，保留作者或机构及来源链接。", true),
	/** 易于理解：简化表达，同时保留事实和必要限定条件。 */
	EASY_TO_UNDERSTAND("易于理解", "拆分冗长句子，优先使用直接、易理解的表达，不丢失关键限定条件。", false),
	/** 流畅度优化：改善句段衔接，保持原文事实与立场。 */
	FLUENCY_OPTIMIZATION("流畅度优化", "优化句间和段落衔接，保持原文事实、立场和结构层次。", false),
	/** 独特词汇：提高表达辨识度，避免生造概念或改变原意。 */
	UNIQUE_WORDS("独特词汇", "在不改变原意的前提下使用更具辨识度的表达，避免堆砌和生造概念。", false),
	/** 技术术语：合理使用已有专业术语，并补充必要解释。 */
	TECHNICAL_TERMS("技术术语", "使用正文或参考资料已有的必要专业术语，并在首次出现时补充简短解释。", false),
	/** 关键词堆砌：保留为论文对照标识，优化服务会拒绝执行此策略。 */
	KEYWORD_STUFFING("关键词堆砌", "仅用于论文对照，不能作为可发布内容的优化策略。", false);

	/**
	 * 策略中文名称
	 */
	private final String displayName;

	/**
	 * 策略执行要求
	 */
	private final String instruction;

	/** 是否必须提供参考资料；用于优化前校验，防止编造数据或引文。 */
	private final boolean requiresReference;

	/**
	 * 判断策略是否必须依赖可核验的参考资料。
	 *
	 * @return true 表示缺少参考资料时不得执行
	 */
	public boolean requiresReference() {
		return requiresReference;
	}

	/**
	 * 判断策略是否仅用于论文实验对照。
	 *
	 * @return true 表示该策略不能生成可发布内容
	 */
	public boolean isComparisonOnly() {
		return this == KEYWORD_STUFFING;
	}
}
