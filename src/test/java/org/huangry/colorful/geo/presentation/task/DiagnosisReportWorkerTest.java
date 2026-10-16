package org.huangry.colorful.geo.presentation.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisResultAnalysisPrompt;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisResultRuleAnalyzer;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisReportSummarizer;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisResultOutputService;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisResultOutputService.ReportAnswer;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMStrategyRouter;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanExecRecordResultEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisResultOutputEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 报告生成编排：冻结默认判定渠道、逐条判定、失败不重试继续下一条、全部判定后汇总；不连接数据库与模型。 */
class DiagnosisReportWorkerTest {
    private final DiagnosisReportWorker worker = new DiagnosisReportWorker();
    private final GeoDiagnosisResultOutputService service = mock(GeoDiagnosisResultOutputService.class);
    private final DiagnosisResultAnalysisPrompt analysisPrompt = mock(DiagnosisResultAnalysisPrompt.class);
    private final DiagnosisReportSummarizer summarizer = mock(DiagnosisReportSummarizer.class);
    private final LLMClient llmClient = mock(LLMClient.class);
    private final LLMStrategyRouter router = mock(LLMStrategyRouter.class);
    private final DiagnosisTaskSnapshots snapshots = mock(DiagnosisTaskSnapshots.class);
    private final GeoLlmChannelEntity channel = new GeoLlmChannelEntity();

    /** 首次生成冻结默认渠道；一条判定成功、一条模型失败继续执行，最后汇总并完成报告。 */
    @Test
    void shouldJudgeEachAnswerAndFinishDespiteSingleFailure() {
        when(service.nextPendingAnalysis(9L)).thenReturn(analysis(1L), analysis(2L), null);
        when(llmClient.query(any(), same(channel))).thenReturn(new LLMResponse("{}", List.of()))
                .thenThrow(new LLMClientException("调用失败"));
        when(analysisPrompt.parse(any(), any(), any(), any())).thenReturn(new DiagnosisResultAnalysisPrompt.AnalysisResult(new JSONArray(), new JSONArray(), null, null));
        when(service.loadAnswers(9L)).thenReturn(List.of(new ReportAnswer(succeeded(), new GeoDiagnosisPlanExecRecordResultEntity())));
        when(summarizer.collectDimensions(any())).thenReturn(Set.of());
        when(summarizer.summarize(any(), any(), any(), any(), any())).thenReturn(new JSONObject().fluentPut("sample", new JSONObject()));

        ReflectionTestUtils.invokeMethod(worker, "generate", report());

        verify(service).freezeJudgeChannel(eq(9L), any(), eq(DiagnosisResultAnalysisPrompt.PROMPT_VERSION));
        verify(service).saveAnalysis(argThat(saved -> saved.getId() == 1L));
        verify(service).failAnalysis(eq(2L), eq("判定模型不可用或未返回有效回答"), any());
        verify(service).finish(eq(9L), contains("sample"));
        verify(service, never()).fail(anyLong(), any());
    }

    /** 全部判定失败时报告失败，不调用维度归一与汇总。 */
    @Test
    void shouldFailReportWhenNoAnswerJudged() {
        when(service.nextPendingAnalysis(9L)).thenReturn(null);
        var failed = new GeoDiagnosisResultAnalysisEntity();
        failed.setStatus("FAILED");
        when(service.loadAnswers(9L)).thenReturn(List.of(new ReportAnswer(failed, new GeoDiagnosisPlanExecRecordResultEntity())));

        ReflectionTestUtils.invokeMethod(worker, "generate", report());

        verify(service).fail(9L, "全部回答判定失败，无法生成报告");
        verify(service, never()).finish(anyLong(), any());
        verify(summarizer, never()).summarize(any(), any(), any(), any(), any());
    }

    /** 没有已启用渠道时报告直接失败，不进入判定。 */
    @Test
    void shouldFailReportWithoutEnabledChannel() {
        when(router.selectDefaultChannel()).thenThrow(new LLMClientException("没有符合渠道及模型条件的已启用模型配置"));

        ReflectionTestUtils.invokeMethod(worker, "generate", report());

        verify(service).fail(9L, "没有已启用的模型渠道，无法判定");
        verify(service, never()).nextPendingAnalysis(anyLong());
    }

    @BeforeEach
    void setup() throws Exception {
        Connection lease = mock(Connection.class);
        when(lease.isValid(anyInt())).thenReturn(true);
        ReflectionTestUtils.setField(worker, "lease", lease);
        ReflectionTestUtils.setField(worker, "service", service);
        ReflectionTestUtils.setField(worker, "ruleAnalyzer", new DiagnosisResultRuleAnalyzer());
        ReflectionTestUtils.setField(worker, "analysisPrompt", analysisPrompt);
        ReflectionTestUtils.setField(worker, "summarizer", summarizer);
        ReflectionTestUtils.setField(worker, "snapshots", snapshots);
        ReflectionTestUtils.setField(worker, "llmClient", llmClient);
        ReflectionTestUtils.setField(worker, "router", router);
        ReflectionTestUtils.setField(worker, "channelService", mock(GeoLlmChannelService.class));
        channel.setChannelCode("analysisPrompt");
        when(router.selectDefaultChannel()).thenReturn(channel);
        when(snapshots.channel(channel)).thenReturn(new JSONObject().fluentPut("id", 1));
        when(snapshots.read(any())).thenAnswer(invocation -> JSON.parse(invocation.<String>getArgument(0)));
        when(service.taskSnapshot(30L)).thenReturn(new JSONObject().fluentPut("project", new JSONObject().fluentPut("name", "团油")));
        when(service.publishedUrls()).thenReturn(Set.of());
        var result = new GeoDiagnosisPlanExecRecordResultEntity();
        result.setAnswerText("回答");
        result.setCitations("[]");
        result.setQuestionSnapshot("{\"text\":\"问题\",\"brandScope\":\"NON_BRAND\"}");
        when(service.getResult(anyLong())).thenReturn(result);
        when(analysisPrompt.buildPrompt(any(), any(), any())).thenReturn("判定提示词");
    }

    /** @return 待生成的报告，尚未冻结判定渠道 */
    private GeoDiagnosisResultOutputEntity report() {
        var report = new GeoDiagnosisResultOutputEntity();
        report.setId(9L);
        report.setExecRecordId(30L);
        report.setStatus("RUNNING");
        return report;
    }

    /**
     * @param id 分析记录 ID
     * @return 待判定记录
     */
    private GeoDiagnosisResultAnalysisEntity analysis(long id) {
        var analysis = new GeoDiagnosisResultAnalysisEntity();
        analysis.setId(id);
        analysis.setResultId(id);
        analysis.setStatus("PENDING");
        return analysis;
    }

    /** @return 判定成功的分析记录 */
    private GeoDiagnosisResultAnalysisEntity succeeded() {
        var analysis = new GeoDiagnosisResultAnalysisEntity();
        analysis.setStatus("SUCCEEDED");
        return analysis;
    }
}
