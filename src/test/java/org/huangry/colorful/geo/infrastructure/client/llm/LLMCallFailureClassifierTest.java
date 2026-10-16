package org.huangry.colorful.geo.infrastructure.client.llm;

import org.huangry.colorful.geo.infrastructure.client.llm.LLMCallFailureClassifier.LLMCallFailure;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMCallFailureType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/** 验证失败分类规则表的顺序与厂商报错读取，样例取自各厂商实测或文档报文。 */
class LLMCallFailureClassifierTest {
    /** DeepSeek 402 与 OpenAI 429 insufficient_quota 均归为余额不足，余额规则优先于限流。 */
    @Test
    void shouldClassifyInsufficientBalanceBeforeRateLimit() {
        LLMCallFailure deepSeek = classify(HttpStatus.PAYMENT_REQUIRED, "{\"error\":{\"message\":\"Insufficient Balance\",\"type\":\"unknown_error\"}}");
        LLMCallFailure openAi = classify(HttpStatus.TOO_MANY_REQUESTS,
                "{\"error\":{\"message\":\"You exceeded your current quota\",\"code\":\"insufficient_quota\"}}");
        LLMCallFailure aliyun = classify(HttpStatus.BAD_REQUEST, "{\"code\":\"Arrearage\",\"message\":\"Access denied, please make sure your account is in good standing.\"}");

        assertEquals(LLMCallFailureType.INSUFFICIENT_BALANCE, deepSeek.type());
        assertEquals(402, deepSeek.httpStatus());
        assertEquals("Insufficient Balance", deepSeek.providerMessage());
        assertEquals(LLMCallFailureType.INSUFFICIENT_BALANCE, openAi.type());
        assertEquals(LLMCallFailureType.INSUFFICIENT_BALANCE, aliyun.type());
    }

    /** 实测报文：智谱 401 密钥错误、千帆 invalid_appId、DeepSeek 模型名错误、智谱错误路径 404。 */
    @Test
    void shouldClassifyByStatusWhenNoBalanceKeyword() {
        assertEquals(LLMCallFailureType.INVALID_KEY,
                classify(HttpStatus.UNAUTHORIZED, "{\"error\":{\"code\":\"401\",\"message\":\"令牌已过期或验证不正确\"}}").type());
        assertEquals(LLMCallFailureType.INVALID_KEY,
                classify(HttpStatus.UNAUTHORIZED, "{\"error\":{\"code\":\"invalid_appId\",\"message\":\"No permission to use the appId\"}}").type());
        LLMCallFailure badModel = classify(HttpStatus.BAD_REQUEST, "{\"error\":{\"message\":\"The supported API model names are "
                + "deepseek-flash, deepseek-v4-pro, but you passed DeepSeek-V4.1-Flash.\"}}");
        assertEquals(LLMCallFailureType.BAD_REQUEST, badModel.type());
        assertTrue(badModel.providerMessage().contains("DeepSeek-V4.1-Flash"));
        assertEquals(LLMCallFailureType.NOT_FOUND,
                classify(HttpStatus.NOT_FOUND, "{\"status\":404,\"error\":\"Not Found\",\"path\":\"/v4/responses\"}").type());
        assertEquals(LLMCallFailureType.RATE_LIMITED, classify(HttpStatus.TOO_MANY_REQUESTS, "{\"message\":\"Over rate limit.\"}").type());
    }

    /** 5xx、网络超时、本地校验与未知异常分别归类，非 JSON 报文按原文截断展示。 */
    @Test
    void shouldClassifyServerNetworkAndClientFailures() {
        String htmlPage = "<html>" + "x".repeat(400) + "</html>";
        LLMCallFailure gateway = LLMCallFailureClassifier.classify(HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "Bad Gateway",
                HttpHeaders.EMPTY, htmlPage.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertEquals(LLMCallFailureType.PROVIDER_ERROR, gateway.type());
        assertEquals(301, gateway.providerMessage().length());

        LLMCallFailure timeout = LLMCallFailureClassifier.classify(new ResourceAccessException("Read timed out", new SocketTimeoutException()));
        assertEquals(LLMCallFailureType.NETWORK, timeout.type());
        assertNull(timeout.httpStatus());

        LLMCallFailure stream = LLMCallFailureClassifier.classify(new LLMClientException("Responses 流在完整回答返回前中断"));
        assertEquals(LLMCallFailureType.INVALID_RESPONSE_OR_CONFIG, stream.type());
        assertEquals("Responses 流在完整回答返回前中断", stream.providerMessage());

        assertEquals(LLMCallFailureType.UNKNOWN, LLMCallFailureClassifier.classify(new IllegalStateException("意外")).type());
    }

    /**
     * @param status 厂商 HTTP 状态码
     * @param body 厂商响应体
     * @return 分类结果
     */
    private LLMCallFailure classify(HttpStatus status, String body) {
        return LLMCallFailureClassifier.classify(HttpClientErrorException.create(status, status.getReasonPhrase(),
                HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
    }
}
