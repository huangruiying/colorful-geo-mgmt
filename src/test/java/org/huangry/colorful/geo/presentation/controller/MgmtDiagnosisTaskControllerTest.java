package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.application.diagnosis.DiagnosisTaskBusiness;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.NoSuchElementException;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 接口缺失与安全错误响应测试，不把 SQL、认证或远程原文返回给页面。 */
class MgmtDiagnosisTaskControllerTest {
    /** 重新发起使用当前任务 ID，活动任务拒绝及缺失任务按既有错误契约返回。 */
    @Test
    void shouldExposeRestartForCurrentTask() throws Exception {
        var business = mock(DiagnosisTaskBusiness.class);
        var controller = new MgmtDiagnosisTaskController();
        ReflectionTestUtils.setField(controller, "diagnosisTaskBusiness", business);
        var mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(post("/diagnosis-tasks/27/restart")).andExpect(status().isOk());
        verify(business).restartTask(27L);
        when(business.restartTask(27L)).thenThrow(new IllegalArgumentException("任务执行中"));
        mvc.perform(post("/diagnosis-tasks/27/restart")).andExpect(status().isBadRequest());
        when(business.restartTask(999L)).thenThrow(new NoSuchElementException("诊断任务不存在"));
        mvc.perform(post("/diagnosis-tasks/999/restart")).andExpect(status().isNotFound());
    }

    /** 缺失任务和诊断结果对应 404，不把失败包装成成功。 */
    @Test
    void shouldReturnNotFoundForMissingTaskAndCrossTaskResult() throws Exception {
        var business = mock(DiagnosisTaskBusiness.class);
        var controller = new MgmtDiagnosisTaskController();
        ReflectionTestUtils.setField(controller, "diagnosisTaskBusiness", business);
        when(business.getTask(999L)).thenThrow(new NoSuchElementException("诊断任务不存在"));
        when(business.getTaskResult(1L, 2L)).thenThrow(new NoSuchElementException("诊断回答不存在或不属于该任务"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/diagnosis-tasks/999")).andExpect(status().isNotFound());
        mvc.perform(get("/diagnosis-tasks/1/results/2")).andExpect(status().isNotFound());
    }

    /** 数据库与渠道错误不得透传凭据。 */
    @Test
    void shouldRedactStorageAndChannelErrors() {
        var controller = new MgmtDiagnosisTaskController();
        var storage = controller.storageFailed(new DataAccessResourceFailureException("private-key-and-sql"));
        var channel = controller.channelInvalid(new LLMClientException("private-key-and-response"));
        assertEquals(500, storage.getStatus()); assertEquals(400, channel.getStatus());
        assertFalse(storage.getDetail().contains("private-key"));
        assertFalse(channel.getDetail().contains("private-key"));
    }
}
