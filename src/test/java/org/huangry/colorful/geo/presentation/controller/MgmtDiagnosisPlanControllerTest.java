package org.huangry.colorful.geo.presentation.controller;

import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.junit.jupiter.api.Test;
import javax.net.ssl.SSLHandshakeException;
import java.net.SocketTimeoutException;
import static org.junit.jupiter.api.Assertions.*;

/** 验证生成错误可定位，同时不把远程原文或凭据返回给页面。 */
class MgmtDiagnosisPlanControllerTest {
    private final MgmtDiagnosisPlanController controller = new MgmtDiagnosisPlanController();

    @Test
    void shouldIdentifyNestedTlsFailureWithoutLeakingRemoteMessage() {
        var exception = new LLMClientException("remote-private",
                new RuntimeException(new SSLHandshakeException("fake-secret")));
        var response = controller.generationFailed(exception);
        assertEquals(502, response.getStatus());
        assertTrue(response.getDetail().contains("HTTPS"));
        assertFalse(response.getDetail().contains("fake-secret"));
        assertFalse(response.getDetail().contains("remote-private"));
    }

    /** 超时提示指向统一 HTTP 配置，候选格式错误不泄露模型原文。 */
    @Test
    void shouldDistinguishTimeoutFromInvalidCandidates() {
        var timeout = controller.generationFailed(new LLMClientException("remote",
                new SocketTimeoutException("private")));
        var format = controller.generationFailed(new LLMClientException("候选无效",
                new IllegalArgumentException("private-answer")));
        assertTrue(timeout.getDetail().contains("超时"));
        assertTrue(timeout.getDetail().contains("统一 HTTP 超时配置"));
        assertFalse(timeout.getDetail().contains("渠道超时配置"));
        assertTrue(format.getDetail().contains("格式"));
        assertFalse(format.getDetail().contains("private-answer"));
    }

    @Test
    void shouldKeepUnknownErrorsGenericAndPreserveExistingQuestions() {
        var response = controller.generationFailed(new LLMClientException("private-configuration"));
        assertTrue(response.getDetail().contains("已有问题和候选保留"));
        assertFalse(response.getDetail().contains("private-configuration"));
    }
}
