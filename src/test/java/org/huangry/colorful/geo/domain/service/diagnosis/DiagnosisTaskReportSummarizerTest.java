package org.huangry.colorful.geo.domain.service.diagnosis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService.ReportAnswer;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 汇总口径：以一个模型的 7 条回答（含 1 条判定失败）逐项核对。
 * <p>非品牌 4 条：Q1 两次（一次首推、一次未提及）、Q2 两次（一次排第 3 被推荐、一次提及未推荐）；点名 2 条：正面、负面。</p>
 */
class DiagnosisTaskReportSummarizerTest {
    private final DiagnosisTaskReportSummarizer summarizer = summarizer();
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

    /** 维度归一只收集三类观点；归一结果缺项或损坏时保持原名。 */
    @Test
    void shouldCollectAndParseDimensions() {
        Set<String> dimensions = summarizer.collectDimensions(answers());
        assertEquals(Set.of("价格偏高", "性价比", "油品"), dimensions);

        Map<String, String> mapping = summarizer.parseDimensionMapping("{\"价格偏高\":\"价格\"}", dimensions);
        assertEquals("价格", mapping.get("价格偏高"));
        assertEquals("油品", mapping.get("油品"));
        assertEquals("性价比", summarizer.parseDimensionMapping("不是 JSON", dimensions).get("性价比"));
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
        result.setQuestionSnapshot("{\"brandScope\":\"" + brandScope + "\",\"intent\":\"RECOMMENDATION\"}");
        var analysis = new GeoDiagnosisTaskResultAnalysisEntity();
        analysis.setStatus(status);
        analysis.setBrands(brands);
        analysis.setPoints(points);
        analysis.setBrandSentiment(sentiment);
        return new ReportAnswer(analysis, result);
    }

    /** @return 注入规则与快照组件的汇总器 */
    private static DiagnosisTaskReportSummarizer summarizer() {
        var summarizer = new DiagnosisTaskReportSummarizer();
        ReflectionTestUtils.setField(summarizer, "ruleAnalyzer", new DiagnosisTaskResultRuleAnalyzer());
        ReflectionTestUtils.setField(summarizer, "snapshots", new DiagnosisTaskSnapshots());
        return summarizer;
    }
}
