package org.huangry.colorful.geo.domain.service.diagnosis;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisAnswerAnalysisDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisPlanExecRecordDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisReportDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanExecRecordEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisReportEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.NoSuchElementException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 创建诊断报告的前置校验与有效回答口径；事务回滚与 SQL 实际执行另用真实 MySQL 验证。 */
class GeoDiagnosisReportServiceTest {
    private final GeoDiagnosisReportService service = new GeoDiagnosisReportService();
    private final GeoDiagnosisReportDao reports = mock(GeoDiagnosisReportDao.class);
    private final GeoDiagnosisAnswerAnalysisDao analyses = mock(GeoDiagnosisAnswerAnalysisDao.class);
    private final GeoDiagnosisPlanExecRecordDao tasks = mock(GeoDiagnosisPlanExecRecordDao.class);

    /** 已结束且有成功结果的任务创建待生成报告，只纳入成功且未截断的回答。 */
    @Test
    void shouldCreatePendingReportWithValidAnswersOnly() {
        for (String status : List.of("SUCCEEDED", "PARTIAL_FAILED")) {
            reset(reports, analyses);
            when(tasks.selectOne(any())).thenReturn(task(status));
            when(reports.insert(any(GeoDiagnosisReportEntity.class))).thenAnswer(invocation -> {
                invocation.<GeoDiagnosisReportEntity>getArgument(0).setId(9L);
                return 1;
            });
            when(analyses.insertPendingFromResults(9L, 30L, "SUCCEEDED", "length", "PENDING")).thenReturn(5);
            var saved = new GeoDiagnosisReportEntity();
            saved.setId(9L);
            when(reports.selectById(9L)).thenReturn(saved);

            assertSame(saved, service.createReport(30L));
            var inserted = org.mockito.ArgumentCaptor.forClass(GeoDiagnosisReportEntity.class);
            verify(reports).insert(inserted.capture());
            assertEquals(30L, inserted.getValue().getExecRecordId());
            assertEquals("PENDING", inserted.getValue().getStatus());
        }
    }

    /** 不存在、执行中或全部失败的任务不能生成报告，不创建任何记录。 */
    @Test
    void shouldRejectMissingUnfinishedOrAllFailedTask() {
        when(tasks.selectOne(any())).thenReturn(null);
        assertThrows(NoSuchElementException.class, () -> service.createReport(30L));
        for (String status : List.of("PENDING", "RUNNING", "FAILED")) {
            when(tasks.selectOne(any())).thenReturn(task(status));
            assertThrows(IllegalArgumentException.class, () -> service.createReport(30L));
        }
        verify(reports, never()).insert(any(GeoDiagnosisReportEntity.class));
    }

    /** 已有报告正在生成时拒绝，避免重复判定计费。 */
    @Test
    void shouldRejectWhenReportAlreadyGenerating() {
        when(tasks.selectOne(any())).thenReturn(task("SUCCEEDED"));
        when(reports.exists(any())).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.createReport(30L));
        verify(reports, never()).insert(any(GeoDiagnosisReportEntity.class));
    }

    /** 没有有效回答时抛出异常，由事务回滚已插入的报告。 */
    @Test
    void shouldRejectWhenNoValidAnswer() {
        when(tasks.selectOne(any())).thenReturn(task("PARTIAL_FAILED"));
        when(reports.insert(any(GeoDiagnosisReportEntity.class))).thenAnswer(invocation -> {
            invocation.<GeoDiagnosisReportEntity>getArgument(0).setId(9L);
            return 1;
        });
        when(analyses.insertPendingFromResults(anyLong(), anyLong(), any(), any(), any())).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> service.createReport(30L));
        verify(reports, never()).selectById(any());
    }

    /** 优先续跑中断的 RUNNING 报告，不重复改状态；没有时把最早的 PENDING 报告置为 RUNNING。 */
    @Test
    void shouldClaimRunningReportBeforePendingOne() {
        var running = new GeoDiagnosisReportEntity();
        running.setId(3L);
        running.setStatus("RUNNING");
        when(reports.selectOne(any())).thenReturn(running);
        assertSame(running, service.claimNext());
        verify(reports, never()).update(isNull(), any());

        var pending = new GeoDiagnosisReportEntity();
        pending.setId(4L);
        pending.setStatus("PENDING");
        when(reports.selectOne(any())).thenReturn(null, pending);
        when(reports.update(isNull(), any())).thenReturn(1);
        var claimed = service.claimNext();
        assertEquals("RUNNING", claimed.getStatus());
        assertNotNull(claimed.getStartedAt());
    }

    /** 只有待判定记录能保存结果，写入数量不符时明确失败，避免覆盖已有判定。 */
    @Test
    void shouldRejectSavingAnalysisThatIsNoLongerPending() {
        var analysis = new org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisAnswerAnalysisEntity();
        analysis.setId(8L);
        when(analyses.update(isNull(), any())).thenReturn(0);

        assertThrows(IllegalStateException.class, () -> service.saveAnalysis(analysis));
        assertThrows(IllegalStateException.class, () -> service.failAnalysis(8L, "原因", "[]"));
    }

    @BeforeEach
    void setup() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "diagnosis-report-test");
        for (Class<?> type : List.of(GeoDiagnosisPlanExecRecordEntity.class, GeoDiagnosisReportEntity.class,
                org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisAnswerAnalysisEntity.class)) {
            TableInfoHelper.initTableInfo(assistant, type);
        }
        ReflectionTestUtils.setField(service, "reportDao", reports);
        ReflectionTestUtils.setField(service, "analysisDao", analyses);
        ReflectionTestUtils.setField(service, "taskDao", tasks);
    }

    /**
     * @param status 诊断任务状态
     * @return 指定状态的诊断任务
     */
    private GeoDiagnosisPlanExecRecordEntity task(String status) {
        var task = new GeoDiagnosisPlanExecRecordEntity();
        task.setId(30L);
        task.setStatus(status);
        return task;
    }
}
