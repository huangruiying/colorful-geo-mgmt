package org.huangry.colorful.geo.domain.service.diagnosis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 判定提示词与输出校验：名称统一、原文片段校验、枚举与分组限制；样例取自本地真实回答。 */
class DiagnosisTaskResultAnalysisPromptTest {
    private final DiagnosisTaskResultAnalysisPrompt analysisPrompt = analysisPrompt();
    private final JSONObject project = JSON.parseObject("""
            {"name":"团油","aliases":["能链团油","车主邦"],"competitors":["易加油","支付宝加油服务","小桔加油（滴滴加油）"],
             "coreFeatures":"加油优惠","sellingPoints":"站点多","pricing":"按站点","targetAudience":"车主"}""");
    private final String answer = "## 主流加油优惠APP\n- **滴滴加油**：接入多个加油站,经常有满减\n- **团油**：覆盖面广,新用户优惠较多\n"
            + "- **支付宝**：生活板块里有加油优惠\n1. **多平台比价**：建议同时装2-3个比较";

    /** 目标统一为项目名，竞品简称归到预设名，对不上预设的为待确认产品，同一产品只保留一次。 */
    @Test
    void shouldNormalizeTargetAndCompetitorNames() {
        var result = analysisPrompt.parse("""
                {"brands":[{"name":"能链团油","type":"TARGET","recommended":true,"rank":2,"strength":"PARALLEL"},
                  {"name":"支付宝","type":"COMPETITOR","recommended":true,"rank":null,"strength":"PASSING"},
                  {"name":"滴滴加油","type":"CANDIDATE","recommended":true,"rank":1,"strength":"PARALLEL"},
                  {"name":"美团","type":"COMPETITOR","recommended":false,"strength":"UNKNOWN"},
                  {"name":"团油","type":"TARGET","recommended":false}],"points":[]}""", answer, "NON_BRAND", project);

        assertEquals(4, result.brands().size());
        assertEquals("团油", result.brands().getJSONObject(0).getString("name"));
        assertEquals(2, result.brands().getJSONObject(0).getIntValue("rank"));
        assertEquals("支付宝加油服务", result.brands().getJSONObject(1).getString("name"));
        assertEquals("小桔加油（滴滴加油）", result.brands().getJSONObject(2).getString("name"));
        assertEquals("COMPETITOR", result.brands().getJSONObject(2).getString("type"));
        assertEquals("CANDIDATE", result.brands().getJSONObject(3).getString("type"));
        assertNull(result.brands().getJSONObject(3).getString("strength"));
    }

    /** 省略 Markdown 符号或把换行写成 \\n 的引用保留；改写、未知或缺失类型、维度胜负缺结果的观点丢弃，不中断解析。 */
    @Test
    void shouldKeepFormatDifferencesButDropFabricatedQuotes() {
        var result = analysisPrompt.parse("""
                ```json
                {"brands":[],"points":[
                  {"type":"SELECTION_CRITERION","dimension":"比价","content":"多平台比价","quote":"1. 多平台比价：建议同时装2-3个比较"},
                  {"type":"RECOMMEND_REASON","brand":"团油","dimension":"覆盖","content":"覆盖广","quote":"- 团油：覆盖面广,新用户优惠较多\\n"},
                  {"type":"USER_CONCERN","dimension":"价格","content":"改写","quote":"团油价格最便宜"},
                  {"type":"UNKNOWN","dimension":"x","content":"x","quote":"团油"},
                  {"type":"DIMENSION_RESULT","dimension":"价格","content":"缺胜负","quote":"团油"},
                  {"dimension":"缺类型","content":"x","quote":"团油"}]}
                ```""", answer, "NON_BRAND", project);

        assertEquals(List.of("SELECTION_CRITERION", "RECOMMEND_REASON"),
                result.points().stream().map(point -> ((JSONObject) point).getString("type")).toList());
    }

    /** 品牌情感与描述准确性只在点名问题保留，其他分组即使模型填写也丢弃。 */
    @Test
    void shouldKeepSentimentOnlyForBrandQuestions() {
        String output = "{\"brands\":[],\"brandSentiment\":\"POSITIVE\",\"descriptionAccurate\":false,\"points\":[]}";

        var brand = analysisPrompt.parse(output, answer, "BRAND", project);
        var nonBrand = analysisPrompt.parse(output, answer, "NON_BRAND", project);

        assertEquals("POSITIVE", brand.brandSentiment());
        assertEquals(false, brand.descriptionAccurate());
        assertNull(nonBrand.brandSentiment());
        assertNull(nonBrand.descriptionAccurate());
    }

    /** 输出不是 JSON 时明确失败，由执行器记为判定失败。 */
    @Test
    void shouldRejectNonJsonOutput() {
        assertThrows(IllegalArgumentException.class, () -> analysisPrompt.parse("无法判断", answer, "NON_BRAND", project));
        assertThrows(IllegalArgumentException.class, () -> analysisPrompt.parse("{brands:[", answer, "NON_BRAND", project));
    }

    /** 点名问题附项目资料用于判断准确性，其他分组不附，避免模型用资料替代回答内容。 */
    @Test
    void shouldAttachProjectFactsOnlyForBrandQuestions() {
        String brandPrompt = analysisPrompt.buildPrompt(project, JSON.parseObject("{\"text\":\"团油靠谱吗\",\"brandScope\":\"BRAND\"}"), answer);
        String nonBrandPrompt = analysisPrompt.buildPrompt(project, JSON.parseObject("{\"text\":\"加油省钱\",\"brandScope\":\"NON_BRAND\"}"), answer);

        assertTrue(brandPrompt.contains("核心卖点：站点多"));
        assertFalse(nonBrandPrompt.contains("核心卖点：站点多"));
        assertTrue(nonBrandPrompt.contains("能链团油、车主邦"));
        assertTrue(nonBrandPrompt.contains(answer));
    }

    /** @return 注入规则组件的判定器 */
    private static DiagnosisTaskResultAnalysisPrompt analysisPrompt() {
        var analysisPrompt = new DiagnosisTaskResultAnalysisPrompt();
        ReflectionTestUtils.setField(analysisPrompt, "ruleAnalyzer", new DiagnosisTaskResultRuleAnalyzer());
        return analysisPrompt;
    }
}
