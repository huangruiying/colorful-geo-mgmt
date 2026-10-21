package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 点名问题中模型对目标品牌或资产的评价倾向，用于品牌认知分析；其他问题不判定，数据库保存为空。
 */
public enum DiagnosisBrandSentiment {
    /** 整体评价正面。 */
    POSITIVE,
    /** 客观陈述，没有明显褒贬。 */
    NEUTRAL,
    /** 整体评价负面，或以批评、风险提示为主。 */
    NEGATIVE
}
