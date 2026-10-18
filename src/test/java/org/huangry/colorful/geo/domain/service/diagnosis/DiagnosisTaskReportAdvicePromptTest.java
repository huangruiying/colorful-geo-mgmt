package org.huangry.colorful.geo.domain.service.diagnosis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 优化建议输出校验：只保留引用了已知问题编号、类别和证据状态合法的条目，防止模型编造结论。 */
class DiagnosisTaskReportAdvicePromptTest {
    private final DiagnosisTaskReportAdvicePrompt advicePrompt = new DiagnosisTaskReportAdvicePrompt();
    private final JSONArray issues = JSON.parseArray("[{\"id\":\"P1\",\"title\":\"提及率低\"},{\"id\":\"P2\",\"title\":\"有错误信息\"}]");

    /** 引用不存在编号的发现被丢弃；未知编号从列表中剔除；类别或证据状态不合法的建议被丢弃。 */
    @Test
    void shouldKeepOnlyItemsBackedByKnownIssues() {
        String output = """
                说明文字```json
                {"findings":[{"content":"提及率低","issueIds":["P1","P9"]},{"content":"编造","issueIds":["P9"]}],
                 "actions":[{"title":"纠正价格","category":"CORRECT_INFORMATION","content":"更新官网","evidenceStatus":"ANSWER_STATED","issueIds":["P2"]},
                            {"title":"乱写类别","category":"OTHER","evidenceStatus":"INFERRED","issueIds":["P1"]},
                            {"title":"缺证据状态","category":"ADD_EVIDENCE","issueIds":["P1"]}]}
                ```""";

        JSONObject advice = advicePrompt.parseAdvice(output, issues);

        assertEquals(1, advice.getJSONArray("findings").size());
        assertEquals(List.of("P1"), advice.getJSONArray("findings").getJSONObject(0).getJSONArray("issueIds"));
        assertEquals(1, advice.getJSONArray("actions").size());
        assertEquals("纠正价格", advice.getJSONArray("actions").getJSONObject(0).getString("title"));
    }

    /** 输出不是 JSON 时明确失败，由调用方记为建议生成失败。 */
    @Test
    void shouldRejectNonJsonOutput() {
        assertThrows(IllegalArgumentException.class, () -> advicePrompt.parseAdvice("无法回答", issues));
    }

    /** 提示词带上全部问题编号，模型才能引用。 */
    @Test
    void shouldIncludeIssuesInPrompt() {
        String prompt = advicePrompt.buildPrompt(new JSONObject().fluentPut("name", "团油"), issues);

        assertTrue(prompt.contains("【目标】团油"));
        assertTrue(prompt.contains("\"id\":\"P2\""));
    }
}
