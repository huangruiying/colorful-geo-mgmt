package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.alibaba.fastjson.JSON;
import com.sun.net.httpserver.HttpServer;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.config.RestTemplateConfig;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.support.TestPropertySourceUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 验证 SSE 完成事件、断流和持续有事件时超过单次空闲超时的实际读取。 */
class ResponsesStreamTest {
    /** 完成事件保留最终正文、引用、用量和原始响应，不以增量正文替代最终回答。 */
    @Test
    void shouldReadCompletedResponseAndMetadata() {
        var result = query(": heartbeat\n\nevent: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"部分\"}\n\n"
                + completed());
        assertEquals("最终回答", result.getContent());
        assertEquals("摘要", result.getCitations().get(0).snippet());
        assertEquals("https://example.invalid", result.getCitations().get(0).url());
        assertEquals(7, JSON.parseObject(result.getUsageInfo()).getIntValue("total_tokens"));
        assertEquals("completed", result.getFinishReason());
        assertEquals("resp-test", JSON.parseObject(result.getRawResponse()).getString("id"));
    }

    /** 增量后 EOF、DONE、错误及不完整事件均不能被保存为成功回答。 */
    @Test
    void shouldRejectInterruptedAndFailedStreams() {
        for (String event : new String[]{"data: {\"type\":\"response.output_text.delta\",\"delta\":\"部分\"}\n\n",
                "data: [DONE]\n\n", "data: {\"type\":\"error\"}\n\n",
                "data: {\"type\":\"response.failed\"}\n\n", "data: {\"type\":\"response.incomplete\"}\n\n",
                "data: {\"type\":\"response.completed\"}\n\n"}) {
            assertThrows(LLMClientException.class, () -> query(event));
        }
    }

    /** 多行 data 以及末尾没有空行的完成事件仍按 SSE 数据帧解析。 */
    @Test
    void shouldAcceptMultilineFinalFrameWithoutBlankLine() {
        assertEquals("最终回答", query(completed().replace("\"response\":", "\n data: \"response\":")
                .replace("\n data:", "\ndata:").stripTrailing()).getContent());
    }

    /** 持续事件维持连接读取，耗时超过一秒的流不会按非流式等待方式超时。 */
    @Test
    void shouldConsumeEventsLongerThanIdleTimeout() throws Exception {
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var payload = new AtomicReference<String>();
        server.createContext("/responses", exchange -> {
            payload.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var output = exchange.getResponseBody()) {
                for (int i = 0; i < 4; i++) {
                    output.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8));
                    output.flush();
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("测试服务器被中断", exception);
                    }
                }
                output.write(completed().getBytes(StandardCharsets.UTF_8));
            } finally {
                exchange.close();
            }
        });
        server.start();
        try (var context = new AnnotationConfigApplicationContext()) {
            TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context,
                    "http.llm.timeout-seconds=1", "http.llm.ssl-verify=true");
            context.register(RestTemplateConfig.class, ResponsesApiDoubaoStrategy.class);
            context.refresh();
            var channel = channel();
            channel.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals("最终回答", context.getBean(ResponsesApiDoubaoStrategy.class)
                    .query(new LLMQueryRequest("测试问题"), channel).getContent());
            assertTrue(JSON.parseObject(payload.get()).getBooleanValue("stream"));
        } finally {
            server.stop(0);
        }
    }

    /**
     * 用内存 HTTP 响应验证真实策略解析，不连接外部平台。
     * @param events SSE 响应文本
     * @return 策略返回结果
     */
    private org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse query(String events) {
        var template = new RestTemplate();
        var server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("http://localhost/responses"))
                .andExpect(header("Accept", "text/event-stream"))
                .andRespond(withSuccess(events, MediaType.TEXT_EVENT_STREAM));
        var strategy = new ResponsesApiDoubaoStrategy();
        ReflectionTestUtils.setField(strategy, "llmRestTemplate", template);
        return strategy.query(new LLMQueryRequest("测试问题"), channel());
    }

    /** @return 不包含真实密钥的本地测试渠道 */
    private GeoLlmChannelEntity channel() {
        var channel = new GeoLlmChannelEntity();
        channel.setBaseUrl("http://localhost");
        channel.setModelName("test-model");
        channel.setApiKey("test-only");
        return channel;
    }

    /** @return 含完整正文、引用和用量的最终事件 */
    private String completed() {
        return "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp-test\",\"status\":\"completed\","
                + "\"model\":\"test-model\",\"usage\":{\"total_tokens\":7},\"output\":[{\"type\":\"message\",\"content\":[{"
                + "\"type\":\"output_text\",\"text\":\"最终回答\",\"annotations\":[{\"type\":\"url_citation\","
                + "\"url\":\"https://example.invalid\",\"summary\":\"摘要\"}]}]}]}}\n\n";
    }
}
