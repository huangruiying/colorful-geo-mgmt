package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** 校验供应商搜索参数、实际 HTTP 请求及引用边界，使用本地服务替身，不调用真实平台。 */
class LlmSearchRequestTest {
    private final ObjectMapper mapper = new ObjectMapper();

    /** 搜索专用模型声明搜索选项，普通兼容模型保留生成参数。 */
    @Test
    void shouldSeparateSearchModelFromOrdinaryChat() {
        var strategy = new OpenAiChatCompletionsApiStrategy();
        var config = config("gpt-5-search-api");
        var search = strategy.buildRequestBody(new LLMQueryRequest("测试问题"), config);
        assertEquals(Map.of(), search.get("web_search_options"));
        assertFalse(search.containsKey("temperature"));
        assertFalse(search.containsKey("max_tokens"));
        config.setModelName("ordinary-model");
        var ordinary = strategy.buildRequestBody(new LLMQueryRequest("测试问题"), config);
        assertFalse(ordinary.containsKey("web_search_options"));
        assertEquals(0.3, ordinary.get("temperature"));
        assertEquals(100, ordinary.get("max_tokens"));
    }

    /** 相同模型名称不能让第三方供应商误收 OpenAI 搜索参数。 */
    @Test
    void shouldNotAssumeCustomGatewaySupportsSearch() {
        var body = new CompatibleChatCompletionsApiStrategy().buildRequestBody(
                new LLMQueryRequest("测试问题"), config("gpt-5-search-api"));
        assertFalse(body.containsKey("web_search_options"));
    }

    /** OpenAI 和方舟声明必需搜索，支持方舟的常用供应商编码。 */
    @Test
    void shouldRequireSearchForOpenaiAndDoubaoResponses() {
        for (AbstractResponsesApiStrategy strategy : List.of(new OpenAiResponsesApiStrategy(), new DoubaoResponsesApiStrategy())) {
            var body = strategy.buildRequestBody(
                    new LLMQueryRequest("测试问题"), config("supported-model"));
            assertEquals(List.of(Map.of("type", "web_search")), body.get("tools"));
            assertEquals("required", body.get("tool_choice"));
        }
    }

    /** 普通兼容策略不套用厂商的原生搜索工具。 */
    @Test
    void shouldNotDeclareSearchInCompatibleStrategy() {
        var body = new CompatibleResponsesApiStrategy().buildRequestBody(
                new LLMQueryRequest("测试问题"), config("test-model"));
        assertFalse(body.containsKey("tools"));
        assertFalse(body.containsKey("tool_choice"));
    }

    /** 失败、不完整、只有工具调用的响应不能被当成成功回答。 */
    @Test
    void shouldRejectFailedOrMissingResponsesText() throws Exception {
        var strategy = new OpenAiResponsesApiStrategy();
        for (String json : List.of("{\"status\":\"failed\"}", "{\"status\":\"incomplete\"}",
                "{\"output\":[{\"type\":\"web_search_call\"}]}")) {
            var response = mapper.readTree(json);
            assertThrows(LLMClientException.class, () -> strategy.parseResponse(response));
        }
    }

    /** 搜索召回来源和正文 URL 不是回答引用，缺失位置保留 null。 */
    @Test
    void shouldKeepSearchSourcesSeparateFromAnswerCitations() throws Exception {
        var response = new OpenAiResponsesApiStrategy().parseResponse(mapper.readTree("""
            {"output":[{"type":"web_search_call","action":{"sources":[{"url":"https://unused.example"}]}},
              {"type":"message","content":[{"type":"output_text","text":"https://plain.example","annotations":[
                {"type":"url_citation","url":"https://cited.example","title":"来源","start_index":null},
                {"type":"url_citation","url":"https://cited.example"},
                {"type":"url_citation","url":""},
                {"type":"other","url":"https://other.example"}]}]}]}
            """));
        assertEquals(1, response.citations().size());
        assertEquals("https://cited.example", response.citations().get(0).url());
        assertNull(response.citations().get(0).startIndex());
    }

    /** Chat Completions 的搜索参数经过真实 HTTP 序列化，引用能够完整回读。 */
    @Test
    void shouldSendChatSearchAndReadCitationOverHttp() throws Exception {
        var captured = new AtomicReference<String>();
        var server = server("/v1/chat/completions", captured, """
            {"choices":[{"message":{"content":"回答","annotations":[
              {"type":"url_citation","url_citation":{"url":"https://source.example","title":"来源"}}]}}]}
            """);
        try {
            var config = config("gpt-5-search-api");
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var response = new OpenAiChatCompletionsApiStrategy().query(new LLMQueryRequest("测试问题"), config);
            assertTrue(mapper.readTree(captured.get()).has("web_search_options"));
            assertEquals("回答", response.content());
            assertEquals("来源", response.citations().get(0).title());
        } finally {
            server.stop(0);
        }
    }

    /** 豆包的原生搜索参数和扁平引用通过实际 HTTP 路径验证。 */
    @Test
    void shouldSendDoubaoSearchAndReadCitationOverHttp() throws Exception {
        var captured = new AtomicReference<String>();
        var server = server("/api/v3/responses", captured, """
            {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"回答",
              "annotations":[{"type":"url_citation","url":"https://source.example","title":"来源"}]}]}]}
            """);
        try {
            var config = config("supported-model");
            config.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v3");
            var response = new DoubaoResponsesApiStrategy().query(new LLMQueryRequest("测试问题"), config);
            var body = mapper.readTree(captured.get());
            assertEquals("web_search", body.path("tools").get(0).path("type").asText());
            assertEquals("required", body.path("tool_choice").asText());
            assertEquals("https://source.example", response.citations().get(0).url());
        } finally {
            server.stop(0);
        }
    }

    /** 构造非敏感连接配置，不使用本机真实渠道凭据。 */
    private GeoLlmChannelEntity config(String model) {
        var config = new GeoLlmChannelEntity();
        config.setModelName(model);
        config.setApiKey("test-key");
        config.setTemperature(0.3);
        config.setMaxTokens(100);
        config.setTimeoutSeconds(5);
        config.setSslVerify(true);
        return config;
    }

    /** 本地 HTTP 替身捕获请求体并返回固定协议响应。 */
    private HttpServer server(String path, AtomicReference<String> captured, String response) throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(path, exchange -> {
            captured.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        return server;
    }
}
