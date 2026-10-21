package org.huangry.colorful.geo.domain.service.diagnosis.report.summary;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.domain.service.diagnosis.report.analyze.ResultRuleAnalyzer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.calculateRate;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.countMatching;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.groupByChannel;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.listPoints;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.sortByIntDesc;

/**
 * 诊断报告 ⑤ 引用与内容：模型引用了哪些来源，官网和我方发布的内容有没有被用上，竞品靠哪些内容，用户关心的方向有没有我方内容。
 * <p>纯计算，不调用模型、不访问数据库、不抓取网页；来源归类由规则在判定前完成，判定失败的回答也参与来源统计。</p>
 */
@Component
public class ReportSourceSummarizer {
    /** 每个模型保留的来源平台数量。 */
    private static final int PLATFORM_LIMIT = 10;

    @Resource
    private ReportDimensionRanking reportDimensionRanking;

    /**
     * ⑤ 引用与内容：模型的信息从哪来，我方内容有没有被引用。
     * <p>只看有来源的回答；各平台“引用”含义不同（逐句引用、打开的网页、搜索召回），页面提示只在同类平台间比较。</p>
     * @param facts 全部回答事实（含判定失败的，来源归类不依赖判定）
     * @param judged 判定成功的回答，竞品来源与内容差距需要判定结果
     * @param dimensionMapping 维度归一映射
     * @return source 对象：
     *         models 各模型的来源覆盖率与各类来源的引用率；
     *         platforms 各模型引用最多的平台；
     *         ownLinks 被引用的官网与我方发布链接；
     *         competitorLinks 推荐竞品的回答引用了哪些内容；
     *         contentGaps 用户关心的标准下，相关回答有没有引用我方内容
     */
    public JSONObject buildSourceAnalysis(List<ReportAnswerFacts> facts, List<ReportAnswerFacts> judged, Map<String, String> dimensionMapping) {
        return new JSONObject(true)
                .fluentPut("models", listSourceCoverageByModel(facts))
                .fluentPut("platforms", listSourcePlatformsByModel(facts))
                .fluentPut("ownLinks", listOwnLinks(facts))
                .fluentPut("competitorLinks", listCompetitorSupportLinks(judged))
                .fluentPut("contentGaps", listContentGaps(judged, dimensionMapping));
    }

    /**
     * 竞品的来源：推荐了预设竞品的回答引用了哪些网址，看竞品被推荐靠的是哪些内容，按引用回答数降序。
     * @param judged 判定成功的回答
     * @return 每个网址一行：url、title、platform、sourceType、count
     */
    private JSONArray listCompetitorSupportLinks(List<ReportAnswerFacts> judged) {
        Map<String, JSONObject> byUrl = new LinkedHashMap<>();
        for (ReportAnswerFacts fact : judged) {
            boolean recommendsCompetitor = fact.brands().stream().map(JSONObject.class::cast)
                    .anyMatch(brand -> "COMPETITOR".equals(brand.getString("type")) && ReportAnswerFacts.isRecommended(brand));
            if (!recommendsCompetitor) {
                continue;
            }
            for (Object value : fact.sources()) {
                JSONObject source = (JSONObject) value;
                JSONObject row = byUrl.computeIfAbsent(source.getString("url"), key -> new JSONObject(true)
                        .fluentPut("url", key)
                        .fluentPut("title", source.getString("title"))
                        .fluentPut("platform", source.getString("platform"))
                        .fluentPut("sourceType", source.getString("sourceType"))
                        .fluentPut("count", 0));
                row.put("count", row.getIntValue("count") + 1);
            }
        }
        return sortByIntDesc(byUrl.values(), "count");
    }

    /**
     * 内容差距：非品牌问题里回答强调的选择标准和用户顾虑，各有多少条回答提到，其中多少条引用了官网或我方发布内容。
     * <p>一条也没引用我方内容的标准，说明这个方向上我方内容没被模型用上（可能没有、不公开或不好找），需要人工确认。</p>
     * @param judged 判定成功的回答
     * @param dimensionMapping 维度归一映射
     * @return 每个维度一行：dimension、answers、ownCitedAnswers、examples，按提到的回答数降序
     */
    private JSONArray listContentGaps(List<ReportAnswerFacts> judged, Map<String, String> dimensionMapping) {
        Map<String, JSONObject> byDimension = new LinkedHashMap<>();
        for (ReportAnswerFacts fact : judged) {
            if (!fact.isNonBrandQuestion()) {
                continue;
            }
            boolean citesOwnContent = fact.sources().stream().map(JSONObject.class::cast).map(source -> source.getString("sourceType"))
                    .anyMatch(type -> ResultRuleAnalyzer.SOURCE_OWNED.equals(type) || ResultRuleAnalyzer.SOURCE_PUBLISHED.equals(type));
            // 同一回答里同一维度只算一次，计数才是“提到它的回答数”
            Set<String> countedDimensions = new LinkedHashSet<>();
            for (JSONObject point : listPoints(fact.answer())) {
                String type = point.getString("type");
                if (!"SELECTION_CRITERION".equals(type) && !"USER_CONCERN".equals(type) || point.getString("dimension") == null) {
                    continue;
                }
                String dimension = dimensionMapping.getOrDefault(point.getString("dimension"), point.getString("dimension"));
                if (!countedDimensions.add(dimension)) {
                    continue;
                }
                JSONObject row = byDimension.computeIfAbsent(dimension, key -> new JSONObject(true)
                        .fluentPut("dimension", key)
                        .fluentPut("answers", 0)
                        .fluentPut("ownCitedAnswers", 0)
                        .fluentPut("examples", new JSONArray()));
                row.put("answers", row.getIntValue("answers") + 1);
                row.put("ownCitedAnswers", row.getIntValue("ownCitedAnswers") + (citesOwnContent ? 1 : 0));
                reportDimensionRanking.addExample(row.getJSONArray("examples"), fact, point);
            }
        }
        return sortByIntDesc(byDimension.values(), "answers");
    }

    /**
     * 各模型的来源情况：多少回答带来源；带来源的回答里，引用了官网、我方发布、提到目标的第三方内容、提到竞品的内容的各占多少。
     * @param facts 全部回答事实
     * @return 每个模型一行：answers、withSources、sourceCoverage（带来源回答 ÷ 全部回答），
     *         ownedRate、publishedRate、thirdPartyRate、competitorRate（引用了该类来源的回答 ÷ 带来源回答）
     */
    private JSONArray listSourceCoverageByModel(List<ReportAnswerFacts> facts) {
        JSONArray rows = new JSONArray();
        for (List<ReportAnswerFacts> channelFacts : groupByChannel(facts).values()) {
            ReportAnswerFacts first = channelFacts.get(0);
            List<ReportAnswerFacts> withSources = channelFacts.stream().filter(ReportAnswerFacts::hasSources).toList();
            rows.add(new JSONObject(true)
                    .fluentPut("channelId", first.channelId())
                    .fluentPut("channelName", first.channelName())
                    .fluentPut("modelName", first.modelName())
                    .fluentPut("answers", channelFacts.size())
                    .fluentPut("withSources", withSources.size())
                    .fluentPut("sourceCoverage", calculateRate(withSources.size(), channelFacts.size()))
                    .fluentPut("ownedRate", rateOfSourceType(withSources, ResultRuleAnalyzer.SOURCE_OWNED))
                    .fluentPut("publishedRate", rateOfSourceType(withSources, ResultRuleAnalyzer.SOURCE_PUBLISHED))
                    .fluentPut("thirdPartyRate", rateOfSourceType(withSources, ResultRuleAnalyzer.SOURCE_THIRD_PARTY_TARGET))
                    .fluentPut("competitorRate", rateOfSourceType(withSources, ResultRuleAnalyzer.SOURCE_COMPETITOR)));
        }
        return rows;
    }

    /**

     * 计算带来源的回答中，至少引用了一条某类来源的回答占比。
     * @param withSources 带来源的回答
     * @param sourceType 来源类型，取值见 ResultRuleAnalyzer.SOURCE_*
     * @return 至少引用了一条该类来源的回答占比，没有带来源回答时为 null
     */
    private Double rateOfSourceType(List<ReportAnswerFacts> withSources, String sourceType) {
        long matched = countMatching(withSources, fact -> fact.sources().stream()
                .anyMatch(source -> sourceType.equals(((JSONObject) source).getString("sourceType"))));
        return calculateRate(matched, withSources.size());
    }

    /**
     * 各模型偏爱引用哪些平台：按平台统计被引用次数，每个模型保留前 PLATFORM_LIMIT 个。
     * @param facts 全部回答事实
     * @return 每个模型一行：channelName、platforms（platform、count 按次数降序）
     */
    private JSONArray listSourcePlatformsByModel(List<ReportAnswerFacts> facts) {
        JSONArray rows = new JSONArray();
        for (List<ReportAnswerFacts> channelFacts : groupByChannel(facts).values()) {
            // 网址无法解析时没有平台，不计入排行
            Map<String, Long> platformCounts = channelFacts.stream().flatMap(fact -> fact.sources().stream())
                    .map(source -> ((JSONObject) source).getString("platform")).filter(Objects::nonNull)
                    .collect(Collectors.groupingBy(platform -> platform, Collectors.counting()));
            JSONArray platforms = new JSONArray();
            platformCounts.entrySet().stream().sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                    .limit(PLATFORM_LIMIT)
                    .forEach(entry -> platforms.add(new JSONObject(true).fluentPut("platform", entry.getKey()).fluentPut("count", entry.getValue())));
            rows.add(new JSONObject(true)
                    .fluentPut("channelId", channelFacts.get(0).channelId())
                    .fluentPut("channelName", channelFacts.get(0).channelName())
                    .fluentPut("platforms", platforms));
        }
        return rows;
    }

    /**
     * 被引用的我方链接：来源类型为官网或我方发布的网址，统计被几条回答引用，按次数降序。
     * @param facts 全部回答事实
     * @return 每个网址一行：url、title、sourceType、count
     */
    private JSONArray listOwnLinks(List<ReportAnswerFacts> facts) {
        Map<String, JSONObject> byUrl = new LinkedHashMap<>();
        for (ReportAnswerFacts fact : facts) {
            for (Object value : fact.sources()) {
                JSONObject source = (JSONObject) value;
                String sourceType = source.getString("sourceType");
                if (!ResultRuleAnalyzer.SOURCE_OWNED.equals(sourceType) && !ResultRuleAnalyzer.SOURCE_PUBLISHED.equals(sourceType)) {
                    continue;
                }
                // 规则归类时已按网址去重，同一回答里同一网址只出现一次，所以次数就是引用它的回答数
                JSONObject row = byUrl.computeIfAbsent(source.getString("url"), key -> new JSONObject(true)
                        .fluentPut("url", key)
                        .fluentPut("title", source.getString("title"))
                        .fluentPut("sourceType", sourceType)
                        .fluentPut("count", 0));
                row.put("count", row.getIntValue("count") + 1);
            }
        }
        return sortByIntDesc(byUrl.values(), "count");
    }
}
