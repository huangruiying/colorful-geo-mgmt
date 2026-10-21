package org.huangry.colorful.geo.domain.service.diagnosis.report.summary;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.domain.service.diagnosis.report.analyze.ResultRuleAnalyzer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.EXAMPLE_LIMIT;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.listPoints;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.sortByIntDesc;

/**
 * 诊断报告的维度排行：按归一后的维度统计某类观点出现的次数，并附原文示例；②③④⑤ 的排行都用它。
 * <p>纯计算，不调用模型、不访问数据库。</p>
 */
@Component
public class ReportDimensionRanking {
    @Resource
    private ResultRuleAnalyzer resultRuleAnalyzer;

    /**
     * 按归一维度统计某类观点，附原文示例。
     * @param judged 判定成功的回答
     * @param scope 参与统计的回答范围
     * @param pointType 观点类型
     * @param brandNames 非空时只统计针对这些品牌的观点（保留意见传目标名称，竞品推荐理由传竞品名称）；为空时不区分品牌
     * @param dimensionMapping 维度归一映射
     * @return 按次数降序的维度排行
     */
    public JSONArray listDimensionRanking(List<ReportAnswerFacts> judged, Predicate<ReportAnswerFacts> scope, String pointType,
                                          List<String> brandNames, Map<String, String> dimensionMapping) {
        Map<String, JSONObject> byDimension = new LinkedHashMap<>();
        for (ReportAnswerFacts fact : judged) {
            if (!scope.test(fact)) {
                continue;
            }
            for (JSONObject point : listPoints(fact.answer())) {
                // 保留意见、竞品推荐理由只统计针对指定品牌的；选择标准、顾虑、正负面点不区分品牌
                if (!pointType.equals(point.getString("type")) || point.getString("dimension") == null
                        || brandNames != null && !resultRuleAnalyzer.containsAnyName(point.getString("brand"), brandNames)) {
                    continue;
                }
                String dimension = dimensionMapping.getOrDefault(point.getString("dimension"), point.getString("dimension"));
                JSONObject row = byDimension.computeIfAbsent(dimension, key -> new JSONObject(true)
                        .fluentPut("dimension", key)
                        .fluentPut("count", 0)
                        .fluentPut("examples", new JSONArray()));
                row.put("count", row.getIntValue("count") + 1);
                addExample(row.getJSONArray("examples"), fact, point);
            }
        }
        return sortByIntDesc(byDimension.values(), "count");
    }

    /**
     * 给排行行追加一条原文示例，超过 EXAMPLE_LIMIT 条不再追加。
     * @param examples 该行已有的示例
     * @param fact 观点所在的回答
     * @param point 观点
     */
    public void addExample(JSONArray examples, ReportAnswerFacts fact, JSONObject point) {
        if (examples.size() < EXAMPLE_LIMIT) {
            examples.add(new JSONObject(true)
                    .fluentPut("resultId", fact.answer().result().getId())
                    .fluentPut("channelName", fact.channelName())
                    .fluentPut("brand", point.getString("brand"))
                    .fluentPut("quote", point.getString("quote")));
        }
    }
}
