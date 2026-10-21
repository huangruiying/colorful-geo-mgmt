package org.huangry.colorful.geo.application.diagnosis.model;

/** 方案内的问题输入与候选输出；意图和品牌范围独立，来源保留供后续追踪。 */
public record DiagnosisQuestion(String text, Source source, Intent intent, BrandScope brandScope, String language) {
    /** 人工维护或模型生成。 */
    public enum Source { MANUAL, GENERATED }
    /** 用户提问目的，侧面问题通常属于问题解决或行业了解。 */
    public enum Intent { RECOMMENDATION, PROBLEM_SOLVING, COMPARISON, PURCHASE, TECHNICAL, AWARENESS }
    /** 决定是否可纳入自然发现统计，含项目名称或别名的问题不能标为非品牌。 */
    public enum BrandScope { NON_BRAND, BRAND, COMPARISON }
}
