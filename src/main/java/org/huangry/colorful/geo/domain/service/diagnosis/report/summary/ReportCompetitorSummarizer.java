package org.huangry.colorful.geo.domain.service.diagnosis.report.summary;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.domain.service.diagnosis.report.analyze.ResultRuleAnalyzer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.RANKING_LIMIT;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.calculateRate;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.countMatching;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.groupByChannel;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.listPoints;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.sortByIntDesc;

/**
 * 诊断报告 ④ 竞品对比：目标与预设竞品谁更容易被提到和推荐、同题谁排在前面、竞品强在哪些场景、对比维度谁赢、竞品凭什么被推荐、有哪些新出现的同类产品。
 * <p>纯计算，不调用模型、不访问数据库；竞品名称在判定解析时已统一为预设名称，这里按名称精确匹配。</p>
 */
@Component
public class ReportCompetitorSummarizer {
    @Resource
    private ResultRuleAnalyzer resultRuleAnalyzer;
    @Resource
    private ReportDimensionRanking reportDimensionRanking;

    /**
     * ④ 竞品对比：和竞品比，谁更容易被提到和推荐、各比较维度谁赢、竞品凭什么被推荐。
     * @param judged 判定成功的回答
     * @param project 任务快照中的项目资料，取目标名称与预设竞品
     * @param dimensionMapping 维度归一映射
     * @return competitor 对象：
     *         brands 目标与各预设竞品在各模型上的提及率、推荐率、首推率；
     *         headToHead 同一回答里目标与竞品都被推荐时谁排在前面；
     *         advantageScenes 竞品推荐率高于目标的问题意图；
     *         dimensions 对比问题中各维度的胜负次数；
     *         competitorReasons 竞品被推荐的理由排行；
     *         candidates 回答中新出现的同类产品
     */
    public JSONObject buildCompetitorComparison(List<ReportAnswerFacts> judged, JSONObject project, Map<String, String> dimensionMapping) {
        List<String> competitors = resultRuleAnalyzer.convertToStringList(project.get("competitors"));
        Predicate<ReportAnswerFacts> nonBrandOrComparison = fact -> fact.isNonBrandQuestion() || fact.isComparisonQuestion();
        return new JSONObject(true)
                .fluentPut("brands", listBrandVisibilityByModel(judged, project.getString("name"), competitors))
                .fluentPut("headToHead", listHeadToHead(judged.stream().filter(nonBrandOrComparison).toList(), competitors))
                .fluentPut("advantageScenes", listCompetitorAdvantageScenes(judged, competitors))
                .fluentPut("dimensions", listComparisonDimensions(judged, dimensionMapping))
                .fluentPut("competitorReasons", reportDimensionRanking.listDimensionRanking(judged, nonBrandOrComparison, "RECOMMEND_REASON", competitors, dimensionMapping))
                .fluentPut("candidates", listCandidates(judged));
    }

    /**
     * 同题胜率：同一条回答里目标和某竞品都被推荐且都有排名时，目标排在前面的占比。
     * @param facts 判定成功的非品牌与对比问题回答
     * @param competitors 预设竞品名称
     * @return 每个模型、竞品一行：competitor、sameAnswers（两者同时被推荐并排名的回答数）、targetAhead、competitorAhead、winRate
     */
    private JSONArray listHeadToHead(List<ReportAnswerFacts> facts, List<String> competitors) {
        JSONArray rows = new JSONArray();
        for (List<ReportAnswerFacts> channelFacts : groupByChannel(facts).values()) {
            ReportAnswerFacts first = channelFacts.get(0);
            for (String competitor : competitors) {
                long targetAhead = 0;
                long competitorAhead = 0;
                for (ReportAnswerFacts fact : channelFacts) {
                    Integer targetRank = ReportAnswerFacts.rankWhenRecommended(fact.target());
                    Integer competitorRank = ReportAnswerFacts.rankWhenRecommended(fact.findBrand(competitor));
                    // 任一方没被推荐或没有明确排名，就比不出先后，不计入
                    if (targetRank == null || competitorRank == null) {
                        continue;
                    }
                    targetAhead += targetRank < competitorRank ? 1 : 0;
                    competitorAhead += competitorRank < targetRank ? 1 : 0;
                }
                rows.add(new JSONObject(true)
                        .fluentPut("channelId", first.channelId())
                        .fluentPut("channelName", first.channelName())
                        .fluentPut("modelName", first.modelName())
                        .fluentPut("competitor", competitor)
                        .fluentPut("sameAnswers", targetAhead + competitorAhead)
                        .fluentPut("targetAhead", targetAhead)
                        .fluentPut("competitorAhead", competitorAhead)
                        .fluentPut("winRate", calculateRate(targetAhead, targetAhead + competitorAhead)));
            }
        }
        return rows;
    }

    /**
     * 竞品优势场景：按模型、问题意图拆分非品牌问题，列出竞品推荐率高于目标的组合。
     * @param judged 判定成功的回答
     * @param competitors 预设竞品名称
     * @return 每个模型、意图、竞品一行：intent、competitor、answers、targetRecommendRate、competitorRecommendRate
     */
    private JSONArray listCompetitorAdvantageScenes(List<ReportAnswerFacts> judged, List<String> competitors) {
        JSONArray rows = new JSONArray();
        Map<Long, Map<String, List<ReportAnswerFacts>>> groups = judged.stream().filter(ReportAnswerFacts::isNonBrandQuestion)
                .collect(Collectors.groupingBy(ReportAnswerFacts::channelId, LinkedHashMap::new,
                        Collectors.groupingBy(fact -> Objects.toString(fact.intent(), "UNKNOWN"), TreeMap::new, Collectors.toList())));
        groups.values().forEach(intents -> intents.forEach((intent, group) -> {
            long targetRecommended = countMatching(group, ReportAnswerFacts::isTargetRecommended);
            for (String competitor : competitors) {
                long competitorRecommended = countMatching(group, fact -> fact.isBrandRecommended(competitor));
                if (competitorRecommended > targetRecommended) {
                    rows.add(new JSONObject(true)
                            .fluentPut("channelId", group.get(0).channelId())
                            .fluentPut("channelName", group.get(0).channelName())
                            .fluentPut("intent", intent)
                            .fluentPut("competitor", competitor)
                            .fluentPut("answers", group.size())
                            .fluentPut("targetRecommendRate", calculateRate(targetRecommended, group.size()))
                            .fluentPut("competitorRecommendRate", calculateRate(competitorRecommended, group.size())));
                }
            }
        }));
        return rows;
    }

    /**
     * 目标与预设竞品放在同一张表里比较：每个模型、每个品牌一行，口径与 ② 的目标指标相同（只算非品牌问题）。
     * <p>对比问题本身就点了竞品的名，算进来提及率会虚高，所以不计入。</p>
     * @param judged 判定成功的回答
     * @param targetName 目标名称
     * @param competitors 预设竞品名称
     * @return 每个模型、品牌一行：brandName、target（是否目标）、nonBrandAnswers、mentionRate、recommendRate、firstChoiceRate
     */
    private JSONArray listBrandVisibilityByModel(List<ReportAnswerFacts> judged, String targetName, List<String> competitors) {
        JSONArray rows = new JSONArray();
        for (List<ReportAnswerFacts> channelFacts : groupByChannel(judged).values()) {
            ReportAnswerFacts first = channelFacts.get(0);
            List<ReportAnswerFacts> nonBrand = channelFacts.stream().filter(ReportAnswerFacts::isNonBrandQuestion).toList();
            // 1. 目标沿用 ② 的判断：提及看规则匹配名称或别名，推荐与首推看判定
            rows.add(buildBrandVisibilityRow(first, targetName, true, nonBrand.size(),
                    countMatching(nonBrand, ReportAnswerFacts::mentioned),
                    countMatching(nonBrand, ReportAnswerFacts::isTargetRecommended),
                    countMatching(nonBrand, ReportAnswerFacts::isTargetFirstChoice)));
            // 2. 竞品：判定列出了该竞品，或回答原文出现了竞品名称，都算提及
            for (String competitor : competitors) {
                rows.add(buildBrandVisibilityRow(first, competitor, false, nonBrand.size(),
                        countMatching(nonBrand, fact -> fact.findBrand(competitor) != null
                                || resultRuleAnalyzer.containsAnyName(fact.answer().result().getAnswerText(), List.of(competitor))),
                        countMatching(nonBrand, fact -> fact.isBrandRecommended(competitor)),
                        countMatching(nonBrand, fact -> fact.isBrandFirstChoice(competitor))));
            }
        }
        return rows;
    }

    /**

     * 组装目标与竞品对比表的一行，比率的分母都是该模型的非品牌回答数。
     * @param channel 该模型任一回答事实，取渠道信息
     * @param brandName 品牌名称
     * @param target 是否目标
     * @param answers 该模型的非品牌回答数
     * @param mentioned 提到该品牌的回答数
     * @param recommended 推荐该品牌的回答数
     * @param firstChoice 首推该品牌的回答数
     * @return 品牌对比表的一行
     */
    private JSONObject buildBrandVisibilityRow(ReportAnswerFacts channel, String brandName, boolean target, int answers,
                                               long mentioned, long recommended, long firstChoice) {
        return new JSONObject(true)
                .fluentPut("channelId", channel.channelId())
                .fluentPut("channelName", channel.channelName())
                .fluentPut("modelName", channel.modelName())
                .fluentPut("brandName", brandName)
                .fluentPut("target", target)
                .fluentPut("nonBrandAnswers", answers)
                .fluentPut("mentionRate", calculateRate(mentioned, answers))
                .fluentPut("recommendRate", calculateRate(recommended, answers))
                .fluentPut("firstChoiceRate", calculateRate(firstChoice, answers));
    }

    /**
     * 分维度胜负：对比问题里，目标在每个比较维度上相对竞品赢、输、平各几次，按出现次数降序。
     * @param judged 判定成功的回答
     * @param dimensionMapping 维度归一映射
     * @return 每个维度一行：dimension、win、lose、tie、examples（原文示例，含对比的竞品）
     */
    private JSONArray listComparisonDimensions(List<ReportAnswerFacts> judged, Map<String, String> dimensionMapping) {
        Map<String, JSONObject> byDimension = new LinkedHashMap<>();
        for (ReportAnswerFacts fact : judged) {
            if (!fact.isComparisonQuestion()) {
                continue;
            }
            for (JSONObject point : listPoints(fact.answer())) {
                if (!"DIMENSION_RESULT".equals(point.getString("type")) || point.getString("dimension") == null) {
                    continue;
                }
                String dimension = dimensionMapping.getOrDefault(point.getString("dimension"), point.getString("dimension"));
                JSONObject row = byDimension.computeIfAbsent(dimension, key -> new JSONObject(true)
                        .fluentPut("dimension", key)
                        .fluentPut("win", 0)
                        .fluentPut("lose", 0)
                        .fluentPut("tie", 0)
                        .fluentPut("examples", new JSONArray()));
                // 解析判定时已保证 outcome 只会是 WIN、LOSE、TIE，转小写后正好是本行的计数字段
                String outcome = point.getString("outcome").toLowerCase(Locale.ROOT);
                row.put(outcome, row.getIntValue(outcome) + 1);
                reportDimensionRanking.addExample(row.getJSONArray("examples"), fact, point);
            }
        }
        JSONArray rows = new JSONArray();
        byDimension.values().stream().sorted((left, right) -> Integer.compare(countOutcomes(right), countOutcomes(left)))
                .limit(RANKING_LIMIT).forEach(rows::add);
        return rows;
    }

    /**

     * 统计一个对比维度的胜、负、平总次数，用于按出现次数排序。
     * @param row 分维度胜负的一行
     * @return 该维度的胜、负、平总次数
     */
    private int countOutcomes(JSONObject row) {
        return row.getIntValue("win") + row.getIntValue("lose") + row.getIntValue("tie");
    }

    /**
     * 新出现的同类产品：判定记为待确认（不在预设竞品里）的产品，统计出现次数与被推荐次数，按出现次数降序。
     * @param judged 判定成功的回答
     * @return 每个产品一行：name、mentions（出现在几条回答里）、recommended（几条回答推荐了它）
     */
    private JSONArray listCandidates(List<ReportAnswerFacts> judged) {
        Map<String, JSONObject> byName = new LinkedHashMap<>();
        for (ReportAnswerFacts fact : judged) {
            for (Object value : fact.brands()) {
                JSONObject brand = (JSONObject) value;
                if (!"CANDIDATE".equals(brand.getString("type"))) {
                    continue;
                }
                JSONObject row = byName.computeIfAbsent(brand.getString("name"), key -> new JSONObject(true)
                        .fluentPut("name", key)
                        .fluentPut("mentions", 0)
                        .fluentPut("recommended", 0));
                row.put("mentions", row.getIntValue("mentions") + 1);
                row.put("recommended", row.getIntValue("recommended") + (ReportAnswerFacts.isRecommended(brand) ? 1 : 0));
            }
        }
        return sortByIntDesc(byName.values(), "mentions");
    }
}
