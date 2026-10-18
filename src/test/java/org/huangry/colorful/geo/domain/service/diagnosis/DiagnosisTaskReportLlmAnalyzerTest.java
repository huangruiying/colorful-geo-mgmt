package org.huangry.colorful.geo.domain.service.diagnosis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService.ReportAnswer;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 报告中需要模型的两步：没有输入时不调用；调用失败时维度保持原名、建议记为失败，不抛出。 */
class DiagnosisTaskReportLlmAnalyzerTest {
    private final LLMClient llmClient = mock(LLMClient.class);
    private final GeoLlmChannelEntity channel = new GeoLlmChannelEntity();
    private final DiagnosisTaskReportLlmAnalyzer llmAnalyzer = llmAnalyzer();

    /** 没有维度、没有问题时不调用模型。 */
    @Test
    void shouldSkipModelWhenNothingToProcess() {
        assertEquals(Map.of(), llmAnalyzer.normalizeDimensions(List.of(), channel, 9L));
        assertEquals("NO_ISSUES", llmAnalyzer.generateAdvice(new JSONArray(), new JSONObject(), channel, 9L).getString("status"));
        verifyNoInteractions(llmClient);
    }

    /** 模型调用失败：维度原样返回，建议标为失败并给出提示。 */
    @Test
    void shouldKeepNamesAndMarkAdviceFailedWhenModelFails() {
        when(llmClient.query(any(), same(channel))).thenThrow(new LLMClientException("调用失败"));

        assertEquals(Map.of("价格偏高", "价格偏高"), llmAnalyzer.normalizeDimensions(answersWithDimension(), channel, 9L));
        JSONObject advice = llmAnalyzer.generateAdvice(JSON.parseArray("[{\"id\":\"P1\"}]"), new JSONObject().fluentPut("name", "团油"), channel, 9L);
        assertEquals("FAILED", advice.getString("status"));
        assertEquals("优化建议生成失败，可先查看规则发现的问题", advice.getString("errorMessage"));
    }

    /** 模型返回归一结果时按映射合并同义维度。 */
    @Test
    void shouldNormalizeDimensionsFromModelOutput() {
        when(llmClient.query(any(), same(channel))).thenReturn(new LLMResponse("{\"价格偏高\":\"价格\"}", List.of()));

        assertEquals(Map.of("价格偏高", "价格"), llmAnalyzer.normalizeDimensions(answersWithDimension(), channel, 9L));
    }

    /** @return 一条判定成功、带“价格偏高”保留意见的回答 */
    private List<ReportAnswer> answersWithDimension() {
        var analysis = new GeoDiagnosisTaskResultAnalysisEntity();
        analysis.setStatus("SUCCEEDED");
        analysis.setPoints("[{\"type\":\"RESERVATION\",\"dimension\":\"价格偏高\",\"quote\":\"贵\"}]");
        return List.of(new ReportAnswer(analysis, new GeoDiagnosisTaskResultEntity()));
    }

    /** @return 使用真实提示词组件与模型替身的分析器 */
    private DiagnosisTaskReportLlmAnalyzer llmAnalyzer() {
        var analyzer = new DiagnosisTaskReportLlmAnalyzer();
        ReflectionTestUtils.setField(analyzer, "llmClient", llmClient);
        ReflectionTestUtils.setField(analyzer, "diagnosisTaskReportDimensionPrompt", new DiagnosisTaskReportDimensionPrompt());
        ReflectionTestUtils.setField(analyzer, "diagnosisTaskReportAdvicePrompt", new DiagnosisTaskReportAdvicePrompt());
        return analyzer;
    }
}
