package org.huangry.colorful.geo.application.diagnosis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskReportLlmAnalyzer;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskReportSummarizer;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskReportDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskReportEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 补全报告：只把 ③–⑥ 并入原汇总，①② 保持生成时的结论；不满足条件时拒绝，不调用模型；同一份报告不重复补全。 */
class DiagnosisTaskReportSupplementBusinessTest {
    private final DiagnosisTaskReportSupplementBusiness business = new DiagnosisTaskReportSupplementBusiness();
    private final GeoDiagnosisTaskReportDao reportDao = mock(GeoDiagnosisTaskReportDao.class);
    private final GeoDiagnosisTaskReportService reportService = mock(GeoDiagnosisTaskReportService.class);
    private final DiagnosisTaskReportSummarizer summarizer = mock(DiagnosisTaskReportSummarizer.class);
    private final DiagnosisTaskReportLlmAnalyzer llmAnalyzer = mock(DiagnosisTaskReportLlmAnalyzer.class);
    private final GeoLlmChannelService channelService = mock(GeoLlmChannelService.class);
    private final DiagnosisTaskReportBusiness reportBusiness = mock(DiagnosisTaskReportBusiness.class);
    private final GeoLlmChannelEntity channel = new GeoLlmChannelEntity();

    /** 重算结果里的 ①② 被丢弃，原汇总的 ①② 保留；③–⑥ 与建议写入，判定渠道沿用报告冻结的那个。 */
    @Test
    void shouldMergeOnlyNewPartsIntoFrozenSummary() {
        when(reportDao.selectById(9L)).thenReturn(report("SUCCEEDED", "{\"sample\":{\"validAnswers\":7},\"models\":[]}"));
        when(summarizer.buildSummary(any(), any(), any(), any(), eq("v1"))).thenReturn(new JSONObject()
                .fluentPut("sample", new JSONObject().fluentPut("validAnswers", 99)).fluentPut("brand", new JSONObject())
                .fluentPut("competitor", new JSONObject()).fluentPut("source", new JSONObject()).fluentPut("issues", new JSONArray()));
        when(llmAnalyzer.generateAdvice(any(), any(), same(channel), eq(9L))).thenReturn(new JSONObject().fluentPut("status", "NO_ISSUES"));
        when(reportService.updateReportSummarySupplemented(eq(9L), any())).thenReturn(true);

        business.supplementReport(9L);

        ArgumentCaptor<String> saved = ArgumentCaptor.forClass(String.class);
        verify(reportService).updateReportSummarySupplemented(eq(9L), saved.capture());
        JSONObject summary = JSON.parseObject(saved.getValue());
        assertEquals(7, summary.getJSONObject("sample").getIntValue("validAnswers"));
        assertTrue(summary.keySet().containsAll(List.of("brand", "competitor", "source", "issues", "advice")));
        assertEquals("NO_ISSUES", summary.getJSONObject("advice").getString("status"));
        verify(reportBusiness).getReport(9L);
    }

    /** 不存在、未完成、已补全的报告都拒绝，且不调用模型。 */
    @Test
    void shouldRejectMissingUnfinishedOrSupplementedReport() {
        when(reportDao.selectById(8L)).thenReturn(report("RUNNING", null));
        when(reportDao.selectById(7L)).thenReturn(report("SUCCEEDED", "{\"sample\":{},\"issues\":[]}"));

        assertThrows(NoSuchElementException.class, () -> business.supplementReport(99L));
        assertEquals("只有已完成的报告可以补全", assertThrows(IllegalArgumentException.class, () -> business.supplementReport(8L)).getMessage());
        assertEquals("该报告内容完整，无需补全", assertThrows(IllegalArgumentException.class, () -> business.supplementReport(7L)).getMessage());
        verifyNoInteractions(llmAnalyzer);
    }

    /** 判定渠道已删除时不换用其他模型；另一次补全先完成时不覆盖。 */
    @Test
    void shouldRejectDeletedChannelOrConcurrentSupplement() {
        when(reportDao.selectById(9L)).thenReturn(report("SUCCEEDED", "{\"sample\":{}}"));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenReturn(new JSONObject().fluentPut("issues", new JSONArray()));
        when(llmAnalyzer.generateAdvice(any(), any(), any(), anyLong())).thenReturn(new JSONObject().fluentPut("status", "NO_ISSUES"));
        when(reportService.updateReportSummarySupplemented(eq(9L), any())).thenReturn(false);

        assertEquals("该报告已补全，请刷新后查看", assertThrows(IllegalArgumentException.class, () -> business.supplementReport(9L)).getMessage());
        when(channelService.getById(6L)).thenReturn(null);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> business.supplementReport(9L)).getMessage().contains("判定渠道已被删除"));
    }

    /** 同一份报告补全期间再次补全被拒绝；补全结束后释放，可以再次进入。 */
    @Test
    void shouldRejectSameReportWhileSupplementing() {
        when(reportDao.selectById(9L)).thenReturn(report("SUCCEEDED", "{\"sample\":{}}"));
        when(summarizer.buildSummary(any(), any(), any(), any(), any())).thenReturn(new JSONObject().fluentPut("issues", new JSONArray()));
        when(llmAnalyzer.generateAdvice(any(), any(), any(), anyLong())).thenReturn(new JSONObject().fluentPut("status", "NO_ISSUES"));
        when(reportService.updateReportSummarySupplemented(eq(9L), any())).thenReturn(true);
        // 第一次补全调用模型期间，同一份报告再次请求补全
        when(llmAnalyzer.normalizeDimensions(any(), any(), anyLong())).thenAnswer(invocation -> {
            assertEquals("该报告正在补全，请稍候", assertThrows(IllegalArgumentException.class, () -> business.supplementReport(9L)).getMessage());
            return Map.of();
        });

        business.supplementReport(9L);
        business.supplementReport(9L);

        verify(reportService, times(2)).updateReportSummarySupplemented(eq(9L), any());
    }

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(business, "geoDiagnosisTaskReportDao", reportDao);
        ReflectionTestUtils.setField(business, "geoDiagnosisTaskReportService", reportService);
        ReflectionTestUtils.setField(business, "diagnosisTaskReportSummarizer", summarizer);
        ReflectionTestUtils.setField(business, "diagnosisTaskReportLlmAnalyzer", llmAnalyzer);
        ReflectionTestUtils.setField(business, "diagnosisTaskSnapshots", new DiagnosisTaskSnapshots());
        ReflectionTestUtils.setField(business, "geoLlmChannelService", channelService);
        ReflectionTestUtils.setField(business, "diagnosisTaskReportBusiness", reportBusiness);
        when(channelService.getById(6L)).thenReturn(channel);
        when(reportService.getTaskSnapshot(30L)).thenReturn(new JSONObject().fluentPut("project", new JSONObject().fluentPut("name", "团油")));
        when(reportService.loadAnswers(anyLong())).thenReturn(List.of());
        when(llmAnalyzer.normalizeDimensions(any(), any(), anyLong())).thenReturn(Map.of());
    }

    /**
     * @param status 报告状态
     * @param summary 冻结的汇总 JSON，可为 null
     * @return 诊断任务 30 的报告 9，判定渠道为 6
     */
    private GeoDiagnosisTaskReportEntity report(String status, String summary) {
        var report = new GeoDiagnosisTaskReportEntity();
        report.setId(9L);
        report.setTaskId(30L);
        report.setStatus(status);
        report.setSummary(summary);
        report.setJudgeChannelSnapshot("{\"id\":6,\"channelName\":\"判定\"}");
        report.setPromptVersion("v1");
        return report;
    }
}
