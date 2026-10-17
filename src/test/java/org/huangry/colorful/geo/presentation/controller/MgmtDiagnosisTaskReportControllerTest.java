package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.application.diagnosis.DiagnosisTaskReportBusiness;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisTaskReportResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.NoSuchElementException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 报告接口契约：生成返回 201，校验失败 400，缺失 404，内部异常不透传。 */
class MgmtDiagnosisTaskReportControllerTest {
    private final DiagnosisTaskReportBusiness business = mock(DiagnosisTaskReportBusiness.class);
    private final MockMvc mvc = createMvc();

    /** 生成报告返回 201 与判定进度；业务拒绝映射为 400 并带原因。 */
    @Test
    void shouldCreateReportOrExplainRejection() throws Exception {
        when(business.create(30L)).thenReturn(new DiagnosisTaskReportResponse(9L, 30L, "加油诊断", "团油", "PENDING",
                new DiagnosisTaskReportResponse.ProgressResponse(70, 70, 0, 0), null, null, null, null, null, null, null))
                .thenThrow(new IllegalArgumentException("该诊断任务已有报告正在生成，请等待完成"));

        mvc.perform(post("/diagnosis-reports/diagnosis-tasks/30"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.progress.total").value(70))
                .andExpect(jsonPath("$.summary").doesNotExist());
        mvc.perform(post("/diagnosis-reports/diagnosis-tasks/30"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("该诊断任务已有报告正在生成，请等待完成"));
    }

    /** 不存在的报告 404；内部状态异常 500 且不透传原文。 */
    @Test
    void shouldMapMissingAndInternalFailures() throws Exception {
        when(business.detail(99L)).thenThrow(new NoSuchElementException("诊断报告不存在"));
        when(business.detail(98L)).thenThrow(new IllegalStateException("sql detail"));

        mvc.perform(get("/diagnosis-reports/99")).andExpect(status().isNotFound());
        mvc.perform(get("/diagnosis-reports/98")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("诊断报告数据暂时无法处理，请稍后重试"));
    }

    /** @return 注入模拟业务的独立 MVC */
    private MockMvc createMvc() {
        var controller = new MgmtDiagnosisTaskReportController();
        ReflectionTestUtils.setField(controller, "diagnosisTaskReportBusiness", business);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }
}
