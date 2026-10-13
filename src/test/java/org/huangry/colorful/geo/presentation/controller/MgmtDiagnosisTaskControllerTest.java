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
    /** 缺失任务和采样对应 404，不把失败包装成成功。 */
    @Test
    void shouldReturnNotFoundForMissingTaskAndCrossTaskResult() throws Exception {
        var business = mock(DiagnosisTaskBusiness.class);
        var controller = new MgmtDiagnosisTaskController();
        ReflectionTestUtils.setField(controller, "business", business);
        when(business.detail(999L)).thenThrow(new NoSuchElementException("诊断任务不存在"));
        when(business.result(1L, 2L)).thenThrow(new NoSuchElementException("诊断回答不存在或不属于该任务"));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
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
