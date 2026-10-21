package org.huangry.colorful.geo.domain.service.diagnosis.report.summary;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService.ReportAnswer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.analyze.ResultRuleAnalyzer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.llm.DimensionPrompt;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 汇总口径：以一个模型的 7 条回答（含 1 条判定失败）逐项核对。
 * <p>非品牌 4 条：Q1 两次（一次首推、一次未提及）、Q2 两次（一次排第 3 被推荐、一次提及未推荐）；点名 2 条：正面、负面。</p>
 */
class ReportSummarizerTest {
    private final ReportSummarizer summarizer = summarizer();
    private final JSONObject project = JSON.parseObject("{\"name\":\"团油\",\"aliases\":[\"能链团油\"],\"competitors\":[\"易加油\"]}");

    /** 非品牌问题的可见度、覆盖率、稳定性，点名问题的正面率与推荐份额按约定口径计算，判定失败不计入。 */
    @Test
    void shouldComputeModelMetrics() {
        JSONObject summary = summarizer.buildSummary(answers(), project, Map.of("价格偏高", "价格"), new JSONObject(), "v1");

        JSONObject sample = summary.getJSONObject("sample");
        assertEquals(7, sample.getIntValue("validAnswers"));
        assertEquals(6, sample.getIntValue("judged"));
        assertEquals(1, sample.getIntValue("judgeFailed"));
        JSONObject model = summary.getJSONArray("models").getJSONObject(0);
        assertEquals(4, model.getIntValue("nonBrandAnswers"));
        assertEquals(0.75, model.getDouble("mentionRate"));
        assertEquals(0.5, model.getDouble("recommendRate"));
        assertEquals(0.25, model.getDouble("firstChoiceRate"));
        assertEquals(2.0, model.getDouble("averageRank"));
        assertEquals(1.0, model.getDouble("questionCoverage"));
        assertEquals(0.5, model.getDouble("stability"));
        assertEquals(0.5, model.getDouble("brandPositiveRate"));
        assertEquals(0.5, model.getDouble("recommendShare"));
    }

    /** 保留意见按归一维度统计目标的缺点；选择标准与顾虑只统计非品牌且未提到目标的回答。 */
    @Test
    void shouldRankDimensionsWithinScope() {
        JSONObject summary = summarizer.buildSummary(answers(), project, Map.of("价格偏高", "价格"), new JSONObject(), "v1");

        assertEquals("价格", summary.getJSONArray("reservations").getJSONObject(0).getString("dimension"));
        assertEquals(1, summary.getJSONArray("reservations").size());
        assertEquals(List.of("性价比"), summary.getJSONArray("criteria").stream().map(row -> ((JSONObject) row).getString("dimension")).toList());
        assertEquals(List.of("油品"), summary.getJSONArray("concerns").stream().map(row -> ((JSONObject) row).getString("dimension")).toList());
        assertEquals(Map.of("PASSING", 1L, "FIRST_CHOICE", 1L, "PARALLEL", 1L),
                summary.getJSONArray("strength").getJSONObject(0).getJSONObject("distribution").getInnerMap());
    }

    /** 点名问题必然提到目标，其推荐强度不计入分布，避免把被动提及算成模型主动推荐。 */
    @Test
    void shouldCountStrengthOnlyForNonBrandQuestions() {
        List<ReportAnswer> answers = new ArrayList<>(answers());
        answers.add(answer(6, 1, "BRAND", "团油值得用", "[{\"name\":\"团油\",\"type\":\"TARGET\",\"recommended\":true,\"rank\":1,\"strength\":\"STRONG\"}]",
                "[]", "POSITIVE", "SUCCEEDED"));

        JSONObject summary = summarizer.buildSummary(answers, project, Map.of(), new JSONObject(), "v1");

        assertEquals(Map.of("PASSING", 1L, "FIRST_CHOICE", 1L, "PARALLEL", 1L),
                summary.getJSONArray("strength").getJSONObject(0).getJSONObject("distribution").getInnerMap());
    }

    /** 维度归一只收集三类观点；归一结果缺项或损坏时保持原名。 */
    @Test
    void shouldCollectAndParseDimensions() {
        var dimensionPrompt = new DimensionPrompt();
        Set<String> dimensions = dimensionPrompt.collectDimensions(answers());
        assertEquals(Set.of("价格偏高", "性价比", "油品"), dimensions);

        Map<String, String> mapping = dimensionPrompt.parseDimensionMapping("{\"价格偏高\":\"价格\"}", dimensions);
        assertEquals("价格", mapping.get("价格偏高"));
        assertEquals("油品", mapping.get("油品"));
        assertEquals("性价比", dimensionPrompt.parseDimensionMapping("不是 JSON", dimensions).get("性价比"));
    }

    /** ③ 品牌认知：点名问题的情感分布、描述准确率、正负面点与错误信息清单。 */
    @Test
    void shouldSummarizeBrandPerceptionFromBrandQuestions() {
        ReportAnswer positive = answer(3, 1, "BRAND", "团油优惠多", "[]",
                "[{\"type\":\"POSITIVE\",\"dimension\":\"优惠\",\"quote\":\"优惠多\"},{\"type\":\"SELLING_POINT_COVERED\",\"dimension\":\"加油优惠\",\"quote\":\"优惠多\"}]",
                "POSITIVE", "SUCCEEDED");
        positive.analysis().setDescriptionAccurate(true);
        ReportAnswer negative = answer(4, 1, "BRAND", "团油要收会员费", "[]",
                "[{\"type\":\"NEGATIVE\",\"dimension\":\"收费\",\"quote\":\"要收会员费\"},{\"type\":\"FACT_ERROR\",\"content\":\"团油不收会员费\",\"quote\":\"要收会员费\"}]",
                "NEGATIVE", "SUCCEEDED");
        negative.analysis().setDescriptionAccurate(false);
        negative.analysis().setSources("[" + source("https://zhihu.com/q/9", "知乎", "OTHER") + "]");

        JSONObject brand = summarizer.buildSummary(List.of(positive, negative), project, Map.of(), new JSONObject(), "v1").getJSONObject("brand");

        JSONObject model = brand.getJSONArray("models").getJSONObject(0);
        assertEquals(2, model.getIntValue("brandAnswers"));
        assertEquals(1, model.getIntValue("positive"));
        assertEquals(0, model.getIntValue("neutral"));
        assertEquals(1, model.getIntValue("negative"));
        assertEquals(0.5, model.getDouble("accurateRate"));
        assertEquals(0.5, model.getDouble("sellingPointRate"));
        JSONObject negativeSource = brand.getJSONArray("negativeSources").getJSONObject(0);
        assertEquals(List.of("知乎", 1), List.of(negativeSource.getString("platform"), negativeSource.getIntValue("count")));
        assertEquals("优惠", brand.getJSONArray("positives").getJSONObject(0).getString("dimension"));
        assertEquals("收费", brand.getJSONArray("negatives").getJSONObject(0).getString("dimension"));
        assertEquals("加油优惠", brand.getJSONArray("sellingPoints").getJSONObject(0).getString("dimension"));
        assertEquals("团油不收会员费", brand.getJSONArray("factErrors").getJSONObject(0).getString("content"));
    }

    /** ④ 竞品对比：目标与竞品同口径（非品牌问题）比较；对比问题统计维度胜负、竞品推荐理由与新出现的产品。 */
    @Test
    void shouldCompareTargetWithCompetitors() {
        List<ReportAnswer> answers = new ArrayList<>(answers());
        answers.add(answer(6, 1, "COMPARISON", "团油和易加油各有优势",
                "[{\"name\":\"团油\",\"type\":\"TARGET\",\"recommended\":true},{\"name\":\"易加油\",\"type\":\"COMPETITOR\",\"recommended\":true},"
                        + "{\"name\":\"小桔加油\",\"type\":\"CANDIDATE\",\"recommended\":true}]",
                "[{\"type\":\"DIMENSION_RESULT\",\"brand\":\"易加油\",\"dimension\":\"价格\",\"outcome\":\"WIN\",\"quote\":\"e\"},"
                        + "{\"type\":\"DIMENSION_RESULT\",\"brand\":\"易加油\",\"dimension\":\"油站数量\",\"outcome\":\"LOSE\",\"quote\":\"f\"},"
                        + "{\"type\":\"RECOMMEND_REASON\",\"brand\":\"易加油\",\"dimension\":\"油站多\",\"quote\":\"g\"}]",
                null, "SUCCEEDED"));

        JSONObject competitor = summarizer.buildSummary(answers, project, Map.of(), new JSONObject(), "v1").getJSONObject("competitor");

        // 非品牌 4 条：易加油出现在 Q1 两次回答中，两次都被推荐，其中一次排第一
        JSONArray brands = competitor.getJSONArray("brands");
        JSONObject target = brands.getJSONObject(0);
        JSONObject rival = brands.getJSONObject(1);
        assertTrue(target.getBooleanValue("target"));
        assertEquals(0.75, target.getDouble("mentionRate"));
        assertEquals("易加油", rival.getString("brandName"));
        assertEquals(0.5, rival.getDouble("mentionRate"));
        assertEquals(0.5, rival.getDouble("recommendRate"));
        assertEquals(0.25, rival.getDouble("firstChoiceRate"));
        // 只有 Q1 第 1 次回答里两者都被推荐且有排名：目标第 1、易加油第 2
        JSONObject headToHead = competitor.getJSONArray("headToHead").getJSONObject(0);
        assertEquals(List.of(1L, 1L, 0L), List.of(headToHead.getLongValue("sameAnswers"), headToHead.getLongValue("targetAhead"), headToHead.getLongValue("competitorAhead")));
        assertEquals(1.0, headToHead.getDouble("winRate"));
        // 推荐类意图里两者推荐率都是 50%，竞品没有更高，不算竞品优势场景
        assertTrue(competitor.getJSONArray("advantageScenes").isEmpty());
        JSONObject price = competitor.getJSONArray("dimensions").getJSONObject(0);
        assertEquals(List.of("价格", 1, 0), List.of(price.getString("dimension"), price.getIntValue("win"), price.getIntValue("lose")));
        JSONObject reason = competitor.getJSONArray("competitorReasons").getJSONObject(0);
        assertEquals("油站多", reason.getString("dimension"));
        assertEquals("易加油", reason.getJSONArray("examples").getJSONObject(0).getString("brand"));
        JSONObject candidate = competitor.getJSONArray("candidates").getJSONObject(0);
        assertEquals(List.of("小桔加油", 1, 1), List.of(candidate.getString("name"), candidate.getIntValue("mentions"), candidate.getIntValue("recommended")));
    }

    /** ⑤ 引用与内容：来源归类不依赖判定，判定失败的回答也计入；比率的分母为带来源的回答。 */
    @Test
    void shouldAnalyzeSourcesIncludingJudgeFailedAnswers() {
        List<ReportAnswer> answers = answers();
        answers.get(0).analysis().setSources("[" + source("https://tuanyou.com/a", "官网", "OWNED") + "," + source("https://zhihu.com/q/1", "知乎", "OTHER") + "]");
        answers.get(1).analysis().setSources("[" + source("https://mp.weixin.qq.com/s/1", "微信公众号", "PUBLISHED") + "," + source("https://zhihu.com/q/2", "知乎", "OTHER") + "]");
        answers.get(6).analysis().setSources("[" + source("https://zhihu.com/q/1", "知乎", "OTHER") + "]");

        JSONObject source = summarizer.buildSummary(answers, project, Map.of(), new JSONObject(), "v1").getJSONObject("source");

        JSONObject model = source.getJSONArray("models").getJSONObject(0);
        assertEquals(7, model.getIntValue("answers"));
        assertEquals(3, model.getIntValue("withSources"));
        assertEquals(0.4286, model.getDouble("sourceCoverage"));
        assertEquals(0.3333, model.getDouble("ownedRate"));
        assertEquals(0.3333, model.getDouble("publishedRate"));
        assertEquals(0.0, model.getDouble("thirdPartyRate"));
        JSONObject topPlatform = source.getJSONArray("platforms").getJSONObject(0).getJSONArray("platforms").getJSONObject(0);
        assertEquals(List.of("知乎", 3L), List.of(topPlatform.getString("platform"), topPlatform.getLongValue("count")));
        assertEquals(List.of("https://tuanyou.com/a", "https://mp.weixin.qq.com/s/1"),
                source.getJSONArray("ownLinks").stream().map(row -> ((JSONObject) row).getString("url")).toList());
        // Q1 两次回答都推荐了易加油，它们引用的 4 个网址都算竞品依靠的来源
        assertEquals(4, source.getJSONArray("competitorLinks").size());
        // 性价比被 Q1 第 2 次、Q2 第 1 次两条回答提到，只有前者引用了我方发布内容
        JSONObject valueForMoney = source.getJSONArray("contentGaps").getJSONObject(0);
        assertEquals(List.of("性价比", 2, 1), List.of(valueForMoney.getString("dimension"), valueForMoney.getIntValue("answers"), valueForMoney.getIntValue("ownCitedAnswers")));
    }

    /** ⑥ 规则问题：按阈值从各部分挑出问题，按加入顺序编号，并带上可追溯的证据。 */
    @Test
    void shouldListIssuesWithEvidence() {
        JSONArray issues = summarizer.buildSummary(answers(), project, Map.of("价格偏高", "价格"), new JSONObject(), "v1").getJSONArray("issues");

        // 提及率 75%、竞品推荐率不高于目标、没有来源，只命中保留意见与两个没被我方内容覆盖的用户关注点
        assertEquals(List.of("P1:RESERVATION", "P2:CONTENT_GAP", "P3:CONTENT_GAP"), issues.stream()
                .map(issue -> ((JSONObject) issue).getString("id") + ":" + ((JSONObject) issue).getString("type")).toList());
        assertEquals("被推荐时常附带缺点：价格", issues.getJSONObject(0).getString("title"));
        assertEquals("c", issues.getJSONObject(0).getJSONArray("evidence").getJSONObject(0).getString("quote"));
    }

    /** ⑥ 规则问题：模型在非品牌问题里从不提到目标时，记为提及率低与问题未覆盖，证据为对应问题。 */
    @Test
    void shouldReportLowMentionWithQuestionEvidence() {
        List<ReportAnswer> answers = List.of(answer(1, 1, "NON_BRAND", "推荐易加油", "[]", "[]", null, "SUCCEEDED"),
                answer(2, 1, "NON_BRAND", "推荐易加油", "[]", "[]", null, "SUCCEEDED"));

        JSONArray issues = summarizer.buildSummary(answers, project, Map.of(), new JSONObject(), "v1").getJSONArray("issues");

        assertEquals(List.of("LOW_MENTION", "MISSED_QUESTIONS"), issues.stream().map(issue -> ((JSONObject) issue).getString("type")).toList());
        assertEquals("能链 claude · claude 有 2 道非品牌问题从未提到目标", issues.getJSONObject(1).getString("title"));
        assertEquals("问题1", issues.getJSONObject(1).getJSONArray("evidence").getJSONObject(0).getString("question"));
    }

    /** @return 规则归类后的一条来源 JSON */
    private String source(String url, String platform, String sourceType) {
        return new JSONObject(true).fluentPut("url", url).fluentPut("title", "标题").fluentPut("platform", platform)
                .fluentPut("sourceType", sourceType).toJSONString();
    }

    /** @return 一个模型的 7 条回答，第 7 条判定失败 */
    private List<ReportAnswer> answers() {
        return List.of(
                answer(1, 1, "NON_BRAND", "首推团油", "[{\"name\":\"团油\",\"type\":\"TARGET\",\"recommended\":true,\"rank\":1,\"strength\":\"FIRST_CHOICE\"},"
                        + "{\"name\":\"易加油\",\"type\":\"COMPETITOR\",\"recommended\":true,\"rank\":2}]", "[]", null, "SUCCEEDED"),
                answer(1, 2, "NON_BRAND", "推荐易加油", "[{\"name\":\"易加油\",\"type\":\"COMPETITOR\",\"recommended\":true,\"rank\":1}]",
                        "[{\"type\":\"SELECTION_CRITERION\",\"dimension\":\"性价比\",\"quote\":\"a\"},{\"type\":\"USER_CONCERN\",\"dimension\":\"油品\",\"quote\":\"b\"}]",
                        null, "SUCCEEDED"),
                answer(2, 1, "NON_BRAND", "也可以用能链团油", "[{\"name\":\"团油\",\"type\":\"TARGET\",\"recommended\":true,\"rank\":3,\"strength\":\"PARALLEL\"}]",
                        "[{\"type\":\"RESERVATION\",\"brand\":\"团油\",\"dimension\":\"价格偏高\",\"quote\":\"c\"},{\"type\":\"SELECTION_CRITERION\",\"dimension\":\"性价比\",\"quote\":\"d\"}]",
                        null, "SUCCEEDED"),
                answer(2, 2, "NON_BRAND", "团油一般", "[{\"name\":\"团油\",\"type\":\"TARGET\",\"recommended\":false,\"strength\":\"PASSING\"}]", "[]", null, "SUCCEEDED"),
                answer(3, 1, "BRAND", "团油不错", "[]", "[]", "POSITIVE", "SUCCEEDED"),
                answer(4, 1, "BRAND", "团油有套路", "[]", "[]", "NEGATIVE", "SUCCEEDED"),
                answer(5, 1, "NON_BRAND", "团油", null, null, null, "FAILED"));
    }

    /**
     * @return 指定问题、采样、分组与判定内容的一条回答
     */
    private ReportAnswer answer(int question, int repeat, String brandScope, String text, String brands, String points, String sentiment, String status) {
        var result = new GeoDiagnosisTaskResultEntity();
        result.setId((long) (question * 10 + repeat));
        result.setQuestionSortOrder(question);
        result.setRepeatIndex(repeat);
        result.setAnswerText(text);
        result.setCitations("[]");
        result.setChannelSnapshot("{\"id\":6,\"channelName\":\"能链 claude\",\"modelName\":\"claude\"}");
        result.setQuestionSnapshot("{\"text\":\"问题" + question + "\",\"brandScope\":\"" + brandScope + "\",\"intent\":\"RECOMMENDATION\"}");
        var analysis = new GeoDiagnosisTaskResultAnalysisEntity();
        analysis.setStatus(status);
        analysis.setBrands(brands);
        analysis.setPoints(points);
        analysis.setBrandSentiment(sentiment);
        return new ReportAnswer(analysis, result);
    }

    /** @return 按 Spring 注入关系手工组装的汇总器：入口类加各页签的汇总类，均使用真实实现 */
    private static ReportSummarizer summarizer() {
        var ruleAnalyzer = new ResultRuleAnalyzer();
        var ranking = new ReportDimensionRanking();
        ReflectionTestUtils.setField(ranking, "resultRuleAnalyzer", ruleAnalyzer);
        var visibility = new ReportVisibilitySummarizer();
        ReflectionTestUtils.setField(visibility, "reportDimensionRanking", ranking);
        var brand = new ReportBrandSummarizer();
        ReflectionTestUtils.setField(brand, "reportDimensionRanking", ranking);
        var competitor = new ReportCompetitorSummarizer();
        ReflectionTestUtils.setField(competitor, "resultRuleAnalyzer", ruleAnalyzer);
        ReflectionTestUtils.setField(competitor, "reportDimensionRanking", ranking);
        var source = new ReportSourceSummarizer();
        ReflectionTestUtils.setField(source, "reportDimensionRanking", ranking);

        var summarizer = new ReportSummarizer();
        ReflectionTestUtils.setField(summarizer, "resultRuleAnalyzer", ruleAnalyzer);
        ReflectionTestUtils.setField(summarizer, "diagnosisTaskSnapshots", new DiagnosisTaskSnapshots());
        ReflectionTestUtils.setField(summarizer, "reportOverviewSummarizer", new ReportOverviewSummarizer());
        ReflectionTestUtils.setField(summarizer, "reportVisibilitySummarizer", visibility);
        ReflectionTestUtils.setField(summarizer, "reportBrandSummarizer", brand);
        ReflectionTestUtils.setField(summarizer, "reportCompetitorSummarizer", competitor);
        ReflectionTestUtils.setField(summarizer, "reportSourceSummarizer", source);
        ReflectionTestUtils.setField(summarizer, "reportIssueSummarizer", new ReportIssueSummarizer());
        return summarizer;
    }
}
