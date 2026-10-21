package org.huangry.colorful.geo.domain.service.diagnosis.report.summary;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService.ReportAnswer;

/**
 * 诊断报告统计用的一条回答事实：由入口汇总类从诊断结果与回答分析整理而来，各页签只读不改。
 * @param answer 原始回答与分析
 * @param channelId 被测渠道 ID
 * @param channelName 被测渠道名称
 * @param modelName 被测模型
 * @param questionSortOrder 问题序号
 * @param questionText 问题原文，用作问题类证据
 * @param brandScope 问题分组
 * @param intent 问题意图
 * @param judged 判定是否成功
 * @param mentioned 是否提到目标
 * @param target 判定中的目标品牌项，未出现时为 null
 * @param brands 判定中的全部品牌项
 * @param searched 是否实际联网
 * @param sources 规则归类后的来源：url、title、domain、platform、sourceType
 */
public record ReportAnswerFacts(ReportAnswer answer, Long channelId, String channelName, String modelName, Integer questionSortOrder,
                                String questionText, String brandScope, String intent, boolean judged, boolean mentioned, JSONObject target,
                                JSONArray brands, boolean searched, JSONArray sources) {
    /** 非品牌问题分组：问品类或场景，没有点名目标。 */
    private static final String NON_BRAND = "NON_BRAND";
    /** 点名问题分组：直接问目标。 */
    private static final String BRAND = "BRAND";
    /** 对比问题分组：目标与竞品对比。 */
    private static final String COMPARISON = "COMPARISON";

    /**
     * 判断是否非品牌问题；可见度类指标只统计这类问题。
     * @return 问题属于非品牌问题
     */
    public boolean isNonBrandQuestion() {
        return NON_BRAND.equals(brandScope);
    }

    /**
     * 判断是否点名问题；品牌认知只统计这类问题。
     * @return 问题属于点名问题
     */
    public boolean isBrandQuestion() {
        return BRAND.equals(brandScope);
    }

    /**
     * 判断是否对比问题；分维度胜负只统计这类问题。
     * @return 问题属于对比问题
     */
    public boolean isComparisonQuestion() {
        return COMPARISON.equals(brandScope);
    }

    /**
     * 判断回答是否带来源：以规则归类后的来源为准，与 ⑤ 引用与内容的口径一致。
     * @return 至少有一条可识别网址的来源
     */
    public boolean hasSources() {
        return !sources.isEmpty();
    }

    /**
     * 判断判定结果中目标是否被推荐。
     * @return 目标被推荐
     */
    public boolean isTargetRecommended() {
        return isRecommended(target);
    }

    /**
     * 判断目标是否被首推。
     * @return 目标被推荐且排第一，或被判定为首推
     */
    public boolean isTargetFirstChoice() {
        return isFirstChoice(target);
    }

    /**
     * 在判定的品牌列表中查找某个品牌。
     * @param brandName 品牌名称；判定解析时已把竞品写法统一为预设名称，因此按名称精确查找
     * @return 判定中该品牌的条目，回答没提到时为 null
     */
    public JSONObject findBrand(String brandName) {
        return brands.stream().map(JSONObject.class::cast).filter(brand -> brandName.equals(brand.getString("name")))
                .findFirst().orElse(null);
    }

    /**
     * 判断某个品牌（如预设竞品）在这条回答里是否被推荐。
     * @param brandName 品牌名称
     * @return 该品牌被推荐；回答没提到时为 false
     */
    public boolean isBrandRecommended(String brandName) {
        return isRecommended(findBrand(brandName));
    }

    /**
     * 判断某个品牌（如预设竞品）在这条回答里是否被首推。
     * @param brandName 品牌名称
     * @return 该品牌被推荐且排第一，或被判定为首推
     */
    public boolean isBrandFirstChoice(String brandName) {
        return isFirstChoice(findBrand(brandName));
    }

    /**
     * 判断判定中的某个品牌条目是否被推荐。
     * @param brand 判定中的品牌条目，可为 null
     * @return 该品牌被推荐
     */
    public static boolean isRecommended(JSONObject brand) {
        return brand != null && Boolean.TRUE.equals(brand.getBoolean("recommended"));
    }

    /**
     * 判断某个品牌是否被首推：被推荐且排第一，或判定为首推。
     * @param brand 判定中的品牌条目，可为 null
     * @return 该品牌被推荐且排第一，或被判定为首推
     */
    public static boolean isFirstChoice(JSONObject brand) {
        return isRecommended(brand) && (Integer.valueOf(1).equals(brand.getInteger("rank"))
                || "FIRST_CHOICE".equals(brand.getString("strength")));
    }

    /**
     * 取品牌被推荐时的排名，用于同题胜率比较先后。
     * @param brand 判定中的品牌条目，可为 null
     * @return 被推荐时的排名；没被推荐或没有排名时为 null
     */
    public static Integer rankWhenRecommended(JSONObject brand) {
        return isRecommended(brand) ? brand.getInteger("rank") : null;
    }
}
