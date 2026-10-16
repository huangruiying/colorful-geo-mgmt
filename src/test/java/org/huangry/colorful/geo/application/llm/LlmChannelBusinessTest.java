package org.huangry.colorful.geo.application.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.huangry.colorful.geo.application.llm.model.SaveLlmChannelRequest;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证实体仅在内部流转，全部对外结果隔离密钥并保持展示字段。 */
class LlmChannelBusinessTest {
    /** 保存、详情、列表和启停均返回无密钥的响应对象。 */
    @Test
    void shouldExcludeCredentialsFromEveryResponse() {
        var service = mock(GeoLlmChannelService.class);
        var business = new LlmChannelBusiness();
        ReflectionTestUtils.setField(business, "service", service);
        ReflectionTestUtils.setField(business, "strategies", java.util.Map.of(
                "responsesApiOpenAiStrategy", new org.huangry.colorful.geo.infrastructure.client.llm.strategy.ResponsesApiOpenAiStrategy()));
        var entity = new GeoLlmChannelEntity();
        entity.setId(1L);
        entity.setApiKey("unit-test-placeholder");
        entity.setStrategyBeanName("responsesApiOpenAiStrategy");
        when(service.getById(1L)).thenReturn(entity);
        when(service.saveChannel(isNull(), any())).thenReturn(entity);
        when(service.listChannels(null, 20, 0)).thenReturn(List.of(entity));
        when(service.changeEnabled(1, true)).thenReturn(entity);
        var results = List.of(business.saveChannel(null, new SaveLlmChannelRequest()),
            business.getChannel(1), business.listChannels(null, 0, 20).items().get(0),
            business.changeEnabled(1, true));
        for (var response : results) {
            var json = new ObjectMapper().valueToTree(response);
            assertFalse(json.has("apiKey"));
            assertTrue(json.path("keyConfigured").asBoolean());
            assertFalse(json.has("searchSupported"));
            assertEquals(1, json.path("id").asLong());
        }
        entity.setApiKey(" ");
        assertFalse(business.getChannel(1).getKeyConfigured());
        assertThrows(java.util.NoSuchElementException.class, () -> business.getChannel(99));
    }

    /** 测试连接先准备配置再调用一次：成功返回摘要与引用数，失败返回分类与厂商原文，均不保存配置。 */
    @Test
    void shouldReportConnectionTestResultWithoutSaving() {
        var service = mock(GeoLlmChannelService.class);
        var router = mock(org.huangry.colorful.geo.infrastructure.client.llm.LLMStrategyRouter.class);
        var business = new LlmChannelBusiness();
        ReflectionTestUtils.setField(business, "service", service);
        ReflectionTestUtils.setField(business, "router", router);
        var request = new SaveLlmChannelRequest();
        request.setChannelCode("deepseek");
        request.setModelName("deepseek-v4-pro");

        var answer = new org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse("今天是10月16日。" + "很长".repeat(200),
                List.of(new org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation("", "https://example.com/news", null, null, "")));
        answer.setModelName("deepseek-v4-pro");
        when(router.query(any(), any(GeoLlmChannelEntity.class))).thenReturn(answer);
        var success = business.testChannel(1L, request);
        assertTrue(success.success());
        assertEquals(1, success.citationCount());
        assertEquals("deepseek-v4-pro", success.modelName());
        assertEquals(201, success.answerPreview().length());
        assertNull(success.failureType());

        when(router.query(any(), any(GeoLlmChannelEntity.class))).thenThrow(org.springframework.web.client.HttpClientErrorException.create(
                org.springframework.http.HttpStatus.PAYMENT_REQUIRED, "Payment Required", org.springframework.http.HttpHeaders.EMPTY,
                "{\"error\":{\"message\":\"Insufficient Balance\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8), null));
        var failure = business.testChannel(1L, request);
        assertFalse(failure.success());
        assertEquals("INSUFFICIENT_BALANCE", failure.failureType());
        assertEquals(402, failure.httpStatus());
        assertEquals("Insufficient Balance", failure.providerMessage());

        var prepared = org.mockito.ArgumentCaptor.forClass(GeoLlmChannelEntity.class);
        verify(service, times(2)).prepareTestChannel(eq(1L), prepared.capture());
        assertEquals("deepseek-v4-pro", prepared.getValue().getModelName());
        verify(service, never()).saveChannel(any(), any());
    }

    /** 配置校验失败时不发起调用，异常交由控制器映射为 400。 */
    @Test
    void shouldNotCallModelWhenTestConfigInvalid() {
        var service = mock(GeoLlmChannelService.class);
        var router = mock(org.huangry.colorful.geo.infrastructure.client.llm.LLMStrategyRouter.class);
        var business = new LlmChannelBusiness();
        ReflectionTestUtils.setField(business, "service", service);
        ReflectionTestUtils.setField(business, "router", router);
        doThrow(new IllegalArgumentException("请填写 API 密钥后再测试")).when(service).prepareTestChannel(isNull(), any());

        assertThrows(IllegalArgumentException.class, () -> business.testChannel(null, new SaveLlmChannelRequest()));
        assertThrows(IllegalArgumentException.class, () -> business.testChannel(null, null));
        verifyNoInteractions(router);
    }
}
