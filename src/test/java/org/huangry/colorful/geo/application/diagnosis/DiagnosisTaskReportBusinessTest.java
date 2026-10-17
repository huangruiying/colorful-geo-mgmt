package org.huangry.colorful.geo.application.diagnosis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskResultAnalysisDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskReportDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskReportEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 报告列表与详情的组装：名称取自任务快照、判定进度按状态汇总、汇总只在详情返回、输入校验。 */
class DiagnosisTaskReportBusinessTest {
    private final DiagnosisTaskReportBusiness business = new DiagnosisTaskReportBusiness();
    private final GeoDiagnosisTaskReportService service = mock(GeoDiagnosisTaskReportService.class);
    private final GeoDiagnosisTaskReportDao reports = mock(GeoDiagnosisTaskReportDao.class);
    private final GeoDiagnosisTaskResultAnalysisDao analyses = mock(GeoDiagnosisTaskResultAnalysisDao.class);
    private final GeoDiagnosisTaskDao tasks = mock(GeoDiagnosisTaskDao.class);

    /** 列表按报告汇总判定进度，名称来自任务快照，不返回冻结汇总。 */
    @Test
    void shouldListReportsWithProgressAndWithoutSummary() {
        when(reports.selectCount(any())).thenReturn(1L);
        when(reports.selectList(any())).thenReturn(List.of(report("{\"models\":[]}")));
        when(analyses.countStatuses(List.of(9L))).thenReturn(List.of(
                Map.of("report_id", 9L, "status", "PENDING", "analysis_count", 2L),
                Map.of("report_id", 9L, "status", "SUCCEEDED", "analysis_count", 5L),
                Map.of("report_id", 9L, "status", "FAILED", "analysis_count", 1L)));

        var page = business.listReports(null, "RUNNING", 0, 10);

        var item = page.items().get(0);
        assertEquals(1L, page.total());
        assertEquals("加油诊断", item.planName());
        assertEquals("团油", item.projectName());
        assertEquals(8, item.progress().total());
        assertEquals(2, item.progress().pending());
        assertEquals(5, item.progress().succeeded());
        assertEquals(1, item.progress().failed());
        assertNull(item.summary());
        assertEquals("qwen-plus", item.judgeChannel().getString("modelName"));
    }

    /** 详情返回冻结汇总；不存在的报告明确 404。 */
    @Test
    void shouldReturnSummaryOnlyInDetail() {
        when(reports.selectById(9L)).thenReturn(report("{\"models\":[{\"mentionRate\":0.5}]}"));
        when(analyses.countStatuses(List.of(9L))).thenReturn(List.of());

        var detail = business.getReport(9L);

        assertEquals(0.5, detail.summary().getJSONArray("models").getJSONObject(0).getDouble("mentionRate"));
        assertEquals(0, detail.progress().total());
        assertThrows(NoSuchElementException.class, () -> business.getReport(99L));
    }

    /** 未选择任务、未知状态与越界分页在调用服务和查询前拒绝。 */
    @Test
    void shouldRejectInvalidInput() {
        assertThrows(IllegalArgumentException.class, () -> business.createReport(0L));
        assertThrows(IllegalArgumentException.class, () -> business.listReports(null, "DONE", 0, 10));
        assertThrows(IllegalArgumentException.class, () -> business.listReports(null, null, 0, 101));
        verifyNoInteractions(service);
        verify(reports, never()).selectList(any());
    }

    @BeforeEach
    void setup() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "diagnosis-report-business-test");
        TableInfoHelper.initTableInfo(assistant, GeoDiagnosisTaskReportEntity.class);
        ReflectionTestUtils.setField(business, "reportService", service);
        ReflectionTestUtils.setField(business, "reportDao", reports);
        ReflectionTestUtils.setField(business, "analysisDao", analyses);
        ReflectionTestUtils.setField(business, "taskDao", tasks);
        ReflectionTestUtils.setField(business, "snapshots", new DiagnosisTaskSnapshots());
        var task = new GeoDiagnosisTaskEntity();
        task.setId(30L);
        task.setPlanSnapshot("{\"planName\":\"加油诊断\",\"project\":{\"name\":\"团油\"}}");
        when(tasks.selectBatchIds(any())).thenReturn(List.of(task));
    }

    /**
     * @param summary 冻结汇总 JSON
     * @return 所属任务 30、已冻结判定渠道的报告
     */
    private GeoDiagnosisTaskReportEntity report(String summary) {
        var report = new GeoDiagnosisTaskReportEntity();
        report.setId(9L);
        report.setTaskId(30L);
        report.setStatus("RUNNING");
        report.setJudgeChannelSnapshot("{\"channelName\":\"千问\",\"modelName\":\"qwen-plus\"}");
        report.setSummary(summary);
        return report;
    }
}
