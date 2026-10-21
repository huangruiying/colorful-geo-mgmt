package org.huangry.colorful.geo.domain.service.diagnosis.report.summary;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.calculateRate;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.countMatching;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.groupByChannel;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.round;

/**
 * 诊断报告 ① 诊断概览：样本说明（有多少回答、判定成败、带来源、联网、模型与题数）和各模型核心指标。
 * <p>纯计算，不调用模型、不访问数据库。可见度类指标只统计非品牌问题，依赖判定的指标只统计判定成功的回答。</p>
 */
@Component
public class ReportOverviewSummarizer {
    /**
     * ① 诊断概览：样本说明与各模型核心指标；② 可见度页也展示这张核心指标表，直接复用，不重复计算。
     * @param facts 全部回答事实（含判定失败的）
     * @param judged 判定成功的回答
     * @param judgeChannel 判定渠道快照
     * @param promptVersion 判定提示词版本
     * @return sample、models 两个字段，直接并入 summary
     */
    public JSONObject buildOverview(List<ReportAnswerFacts> facts, List<ReportAnswerFacts> judged,
                                    JSONObject judgeChannel, String promptVersion) {
        return new JSONObject(true)
                .fluentPut("sample", buildSample(facts, judgeChannel, promptVersion))
                .fluentPut("models", listModelMetrics(judged));
    }

    /**

     * ① 样本说明：有效回答、判定成功与失败、带来源、实际联网、模型数、题数、最大重复执行次数，以及判定口径。
     * @param facts 全部回答事实
     * @param judgeChannel 判定渠道快照
     * @param promptVersion 提示词版本
     * @return 样本说明
     */
    private JSONObject buildSample(List<ReportAnswerFacts> facts, JSONObject judgeChannel, String promptVersion) {
        long judged = countMatching(facts, ReportAnswerFacts::judged);
        return new JSONObject(true)
                .fluentPut("validAnswers", facts.size())
                .fluentPut("judged", judged)
                .fluentPut("judgeFailed", facts.size() - judged)
                .fluentPut("withSources", countMatching(facts, ReportAnswerFacts::hasSources))
                .fluentPut("searched", countMatching(facts, ReportAnswerFacts::searched))
                .fluentPut("models", facts.stream().map(ReportAnswerFacts::channelId).distinct().count())
                .fluentPut("questions", facts.stream().map(ReportAnswerFacts::questionSortOrder).distinct().count())
                .fluentPut("maxRepeat", facts.stream().mapToInt(fact -> fact.answer().result().getRepeatIndex()).max().orElse(0))
                .fluentPut("judgeChannel", judgeChannel)
                .fluentPut("promptVersion", promptVersion);
    }

    /**
     * 按模型计算核心指标：非品牌问题的可见度、点名问题的品牌正面率、非品牌与对比问题的推荐份额。
     * @param judged 判定成功的回答
     * @return 每个模型一行
     */
    private JSONArray listModelMetrics(List<ReportAnswerFacts> judged) {
        JSONArray rows = new JSONArray();
        for (List<ReportAnswerFacts> channelFacts : groupByChannel(judged).values()) {
            ReportAnswerFacts first = channelFacts.get(0);
            List<ReportAnswerFacts> nonBrand = channelFacts.stream().filter(ReportAnswerFacts::isNonBrandQuestion).toList();
            // 1. 可见度：提及、推荐、首推与平均排名，分母为该模型的非品牌回答
            long mentioned = countMatching(nonBrand, ReportAnswerFacts::mentioned);
            long recommended = countMatching(nonBrand, ReportAnswerFacts::isTargetRecommended);
            long firstChoice = countMatching(nonBrand, ReportAnswerFacts::isTargetFirstChoice);
            List<Integer> ranks = nonBrand.stream().filter(ReportAnswerFacts::isTargetRecommended).map(fact -> fact.target().getInteger("rank"))
                    .filter(Objects::nonNull).toList();
            // 2. 品牌正面率：点名问题中有情感判定的回答里正面的占比
            List<ReportAnswerFacts> brandFacts = channelFacts.stream().filter(fact -> fact.isBrandQuestion()
                    && fact.answer().analysis().getBrandSentiment() != null).toList();
            long positive = countMatching(brandFacts, fact -> "POSITIVE".equals(fact.answer().analysis().getBrandSentiment()));
            rows.add(new JSONObject(true)
                    .fluentPut("channelId", first.channelId())
                    .fluentPut("channelName", first.channelName())
                    .fluentPut("modelName", first.modelName())
                    .fluentPut("judgedAnswers", channelFacts.size())
                    .fluentPut("nonBrandAnswers", nonBrand.size())
                    .fluentPut("mentionRate", calculateRate(mentioned, nonBrand.size()))
                    .fluentPut("recommendRate", calculateRate(recommended, nonBrand.size()))
                    .fluentPut("firstChoiceRate", calculateRate(firstChoice, nonBrand.size()))
                    .fluentPut("averageRank", ranks.isEmpty() ? null : round(ranks.stream().mapToInt(Integer::intValue).average().orElse(0)))
                    .fluentPut("questionCoverage", calculateQuestionCoverage(nonBrand))
                    .fluentPut("stability", calculateStability(nonBrand))
                    .fluentPut("brandPositiveRate", calculateRate(positive, brandFacts.size()))
                    .fluentPut("recommendShare", calculateRecommendShare(channelFacts)));
        }
        return rows;
    }

    /**
     * 问题覆盖率：至少有一次提到目标的非品牌问题数 ÷ 非品牌问题数。
     * @param nonBrand 某模型的非品牌回答
     * @return 比率，无非品牌问题时为 null
     */
    private Double calculateQuestionCoverage(List<ReportAnswerFacts> nonBrand) {
        Map<Integer, List<ReportAnswerFacts>> byQuestion = nonBrand.stream().collect(Collectors.groupingBy(ReportAnswerFacts::questionSortOrder));
        long covered = byQuestion.values().stream().filter(group -> group.stream().anyMatch(ReportAnswerFacts::mentioned)).count();
        return calculateRate(covered, byQuestion.size());
    }

    /**
     * 稳定性：同一题重复执行 2 次以上时，各次"提到或没提到"全部一致的题数 ÷ 这类题数。
     * @param nonBrand 某模型的非品牌回答
     * @return 比率，没有重复执行的题时为 null
     */
    private Double calculateStability(List<ReportAnswerFacts> nonBrand) {
        List<List<ReportAnswerFacts>> repeated = nonBrand.stream().collect(Collectors.groupingBy(ReportAnswerFacts::questionSortOrder))
                .values().stream().filter(group -> group.size() >= 2).toList();
        long consistent = countMatching(repeated, group -> group.stream().map(ReportAnswerFacts::mentioned).distinct().count() == 1);
        return calculateRate(consistent, repeated.size());
    }

    /**
     * 推荐份额：非品牌与对比问题中，目标被推荐次数 ÷（目标 + 预设竞品被推荐次数），待确认产品不计入。
     * @param channelFacts 某模型判定成功的回答
     * @return 比率，没有任何推荐时为 null
     */
    private Double calculateRecommendShare(List<ReportAnswerFacts> channelFacts) {
        long target = 0;
        long total = 0;
        for (ReportAnswerFacts fact : channelFacts) {
            if (!fact.isNonBrandQuestion() && !fact.isComparisonQuestion()) {
                continue;
            }
            for (Object value : fact.brands()) {
                JSONObject brand = (JSONObject) value;
                if (Boolean.TRUE.equals(brand.getBoolean("recommended")) && !"CANDIDATE".equals(brand.getString("type"))) {
                    total++;
                    target += "TARGET".equals(brand.getString("type")) ? 1 : 0;
                }
            }
        }
        return calculateRate(target, total);
    }
}
