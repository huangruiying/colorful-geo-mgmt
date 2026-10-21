package org.huangry.colorful.geo.domain.service.diagnosis.report.summary;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.EXAMPLE_LIMIT;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.RANKING_LIMIT;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.calculateRate;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.countMatching;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.groupByChannel;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.listPoints;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.sortByIntDesc;

/**
 * 诊断报告 ③ 品牌认知：用户直接问到目标（点名问题）时，模型怎么评价、说得对不对、负面来自哪里、卖点说没说到。
 * <p>纯计算，不调用模型、不访问数据库；只统计判定成功的点名问题回答。</p>
 */
@Component
public class ReportBrandSummarizer {
    @Resource
    private ReportDimensionRanking reportDimensionRanking;

    /**
     * ③ 品牌认知：用户直接问到目标（点名问题）时，模型怎么评价、说得对不对。
     * @param judged 判定成功的回答
     * @param dimensionMapping 维度归一映射
     * @return brand 对象：
     *         models 各模型的情感分布、描述准确率与卖点覆盖率；
     *         positives、negatives、sellingPoints 正面点、负面点、提到的卖点的维度排行；
     *         negativeSources 负面回答引用的来源平台；
     *         factErrors 与项目资料不符的描述清单
     */
    public JSONObject buildBrandPerception(List<ReportAnswerFacts> judged, Map<String, String> dimensionMapping) {
        Predicate<ReportAnswerFacts> brandQuestion = fact -> fact.isBrandQuestion();
        List<ReportAnswerFacts> brandFacts = judged.stream().filter(brandQuestion).toList();
        return new JSONObject(true)
                .fluentPut("models", listBrandPerceptionByModel(brandFacts))
                .fluentPut("positives", reportDimensionRanking.listDimensionRanking(judged, brandQuestion, "POSITIVE", null, dimensionMapping))
                .fluentPut("negatives", reportDimensionRanking.listDimensionRanking(judged, brandQuestion, "NEGATIVE", null, dimensionMapping))
                .fluentPut("sellingPoints", reportDimensionRanking.listDimensionRanking(judged, brandQuestion, "SELLING_POINT_COVERED", null, dimensionMapping))
                .fluentPut("negativeSources", listNegativeSources(brandFacts))
                .fluentPut("factErrors", listFactErrors(brandFacts));
    }

    /**
     * 各模型在点名问题上的评价：正面、中性、负面各几条，描述准确率多少，说到核心卖点的回答占多少（信息完整度）。
     * @param brandFacts 判定成功的点名问题回答
     * @return 每个模型一行：brandAnswers、positive、neutral、negative、accurateRate、sellingPointRate
     */
    private JSONArray listBrandPerceptionByModel(List<ReportAnswerFacts> brandFacts) {
        JSONArray rows = new JSONArray();
        for (List<ReportAnswerFacts> channelFacts : groupByChannel(brandFacts).values()) {
            ReportAnswerFacts first = channelFacts.get(0);
            Map<String, Long> sentimentCounts = channelFacts.stream().map(fact -> fact.answer().analysis().getBrandSentiment())
                    .filter(Objects::nonNull).collect(Collectors.groupingBy(sentiment -> sentiment, Collectors.counting()));
            // 准确率的分母只算判定给出了对错的回答，判定没填时不算准确也不算错误
            List<Boolean> accuracies = channelFacts.stream().map(fact -> fact.answer().analysis().getDescriptionAccurate())
                    .filter(Objects::nonNull).toList();
            long accurate = countMatching(accuracies, Boolean::booleanValue);
            // 信息完整度：判定在回答里找到至少一个核心卖点（SELLING_POINT_COVERED）即算说到
            long coveredSellingPoint = countMatching(channelFacts, fact -> listPoints(fact.answer()).stream()
                    .anyMatch(point -> "SELLING_POINT_COVERED".equals(point.getString("type"))));
            rows.add(new JSONObject(true)
                    .fluentPut("channelId", first.channelId())
                    .fluentPut("channelName", first.channelName())
                    .fluentPut("modelName", first.modelName())
                    .fluentPut("brandAnswers", channelFacts.size())
                    .fluentPut("positive", sentimentCounts.getOrDefault("POSITIVE", 0L))
                    .fluentPut("neutral", sentimentCounts.getOrDefault("NEUTRAL", 0L))
                    .fluentPut("negative", sentimentCounts.getOrDefault("NEGATIVE", 0L))
                    .fluentPut("accurateRate", calculateRate(accurate, accuracies.size()))
                    .fluentPut("sellingPointRate", calculateRate(coveredSellingPoint, channelFacts.size())));
        }
        return rows;
    }

    /**
     * 负面来源：判定为负面评价的点名回答引用了哪些平台，按次数降序，每个平台附最多 EXAMPLE_LIMIT 个链接，用来决定去哪里处理。
     * @param brandFacts 判定成功的点名问题回答
     * @return 每个平台一行：platform、count、links（url、title、resultId）
     */
    private JSONArray listNegativeSources(List<ReportAnswerFacts> brandFacts) {
        Map<String, JSONObject> byPlatform = new LinkedHashMap<>();
        for (ReportAnswerFacts fact : brandFacts) {
            if (!"NEGATIVE".equals(fact.answer().analysis().getBrandSentiment())) {
                continue;
            }
            for (Object value : fact.sources()) {
                JSONObject source = (JSONObject) value;
                if (source.getString("platform") == null) {
                    continue;
                }
                JSONObject row = byPlatform.computeIfAbsent(source.getString("platform"), key -> new JSONObject(true)
                        .fluentPut("platform", key)
                        .fluentPut("count", 0)
                        .fluentPut("links", new JSONArray()));
                row.put("count", row.getIntValue("count") + 1);
                JSONArray links = row.getJSONArray("links");
                if (links.size() < EXAMPLE_LIMIT) {
                    links.add(new JSONObject(true)
                            .fluentPut("url", source.getString("url"))
                            .fluentPut("title", source.getString("title"))
                            .fluentPut("resultId", fact.answer().result().getId()));
                }
            }
        }
        return sortByIntDesc(byPlatform.values(), "count");
    }

    /**
     * 错误信息清单：点名问题回答中与项目资料不符的描述，按回答顺序列出，最多 RANKING_LIMIT 条。
     * @param brandFacts 判定成功的点名问题回答
     * @return 每条错误一行：resultId、channelId、channelName、content（错在哪）、quote（回答原文）
     */
    private JSONArray listFactErrors(List<ReportAnswerFacts> brandFacts) {
        JSONArray rows = new JSONArray();
        for (ReportAnswerFacts fact : brandFacts) {
            for (JSONObject point : listPoints(fact.answer())) {
                if ("FACT_ERROR".equals(point.getString("type")) && rows.size() < RANKING_LIMIT) {
                    rows.add(new JSONObject(true)
                            .fluentPut("resultId", fact.answer().result().getId())
                            .fluentPut("channelId", fact.channelId())
                            .fluentPut("channelName", fact.channelName())
                            .fluentPut("content", point.getString("content"))
                            .fluentPut("quote", point.getString("quote")));
                }
            }
        }
        return rows;
    }
}
