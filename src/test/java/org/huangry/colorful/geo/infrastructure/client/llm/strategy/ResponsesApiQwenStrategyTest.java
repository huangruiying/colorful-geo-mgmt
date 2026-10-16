package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** 验证千问联网请求契约及搜索来源作为引用的解析，不调用外部模型。 */
class ResponsesApiQwenStrategyTest {
    /** 强制调用百炼搜索并流式读取，保留模型与单次问题配置。 */
    @Test
    void shouldRequireWebSearchWithStreaming() {
        var channel = channel();
        channel.setMaxTokens(2000);
        var body = new ResponsesApiQwenStrategy().buildRequestBody(new LLMQueryRequest("2026年加油优惠App推荐哪些？"), channel);

        assertEquals(List.of(Map.of("type", "web_search")), body.get("tools"));
        assertEquals("required", body.get("tool_choice"));
        assertEquals(true, body.get("stream"));
        assertEquals("qwen3.6-plus", body.get("model"));
        assertEquals(2000, body.get("max_output_tokens"));
        assertEquals(false, body.get("store"));
    }

    /** 搜索来源网址按出现顺序去重为引用，非网址类型和空地址跳过，检索词不计入。 */
    @Test
    void shouldUseSearchSourcesAsCitations() {
        String sources = "[{\"type\":\"url\",\"url\":\"https://www.tuanyou.net/jc/13.html\"},"
                + "{\"type\":\"url\",\"url\":\"https://apps.apple.com/cn/app/id554499054\"},"
                + "{\"type\":\"url\",\"url\":\"https://www.tuanyou.net/jc/13.html\"},"
                + "{\"type\":\"file\",\"url\":\"https://example.invalid/file\"},{\"type\":\"url\",\"url\":\" \"}]";
        LLMResponse result = query(completed(searchCall(sources)));

        assertEquals("推荐团油、滴滴加油。", result.getContent());
        assertEquals(List.of(new LLMCitation("", "https://www.tuanyou.net/jc/13.html", null, null, ""),
                new LLMCitation("", "https://apps.apple.com/cn/app/id554499054", null, null, "")), result.getCitations());
        assertEquals("qwen3.6-plus", result.getModelName());
    }

    /** 搜索调用没有返回来源时引用为空，回答仍按完成事件保存。 */
    @Test
    void shouldLeaveCitationsEmptyWithoutSources() {
        LLMResponse result = query(completed("{\"type\":\"web_search_call\",\"status\":\"completed\","
                + "\"action\":{\"type\":\"search\",\"query\":\"加油优惠\"}},"));

        assertEquals("推荐团油、滴滴加油。", result.getContent());
        assertTrue(result.getCitations().isEmpty());
    }

    /** 千问策略声明百炼密钥获取页，供配置页面引导。 */
    @Test
    void shouldDeclareBailianApiKeyConsole() {
        assertEquals("https://bailian.console.aliyun.com/?tab=model#/api-key", new ResponsesApiQwenStrategy().apiKeyConsoleUrl());
    }

    /**
     * 用内存 HTTP 响应验证真实策略的流式读取和解析，不连接外部平台。
     * @param events SSE 响应文本
     * @return 策略返回结果
     */
    private LLMResponse query(String events) {
        var template = new RestTemplate();
        var server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("http://localhost/responses"))
                .andExpect(header("Accept", "text/event-stream"))
                .andRespond(withSuccess(events, MediaType.TEXT_EVENT_STREAM));
        var strategy = new ResponsesApiQwenStrategy();
        ReflectionTestUtils.setField(strategy, "llmRestTemplate", template);
        LLMResponse result = strategy.query(new LLMQueryRequest("测试问题"), channel());
        server.verify();
        return result;
    }

    /** @return 不包含真实密钥的本地测试渠道 */
    private GeoLlmChannelEntity channel() {
        var channel = new GeoLlmChannelEntity();
        channel.setBaseUrl("http://localhost");
        channel.setModelName("qwen3.6-plus");
        channel.setApiKey("test-only");
        return channel;
    }

    /**
     * 按百炼实测格式组装事件：data 无空格、带 id/event 行，完成事件含推理、搜索记录和无注解正文。
     * @param searchCalls 已序列化的 web_search_call 项，以逗号结尾
     * @return SSE 文本
     */
    private String completed(String searchCalls) {
        return "id:1\nevent:response.created\n:HTTP_STATUS/200\ndata:{\"type\":\"response.created\"}\n\n"
                + "id:2\nevent:response.completed\ndata:{\"type\":\"response.completed\",\"response\":{\"id\":\"resp-qwen\","
                + "\"status\":\"completed\",\"model\":\"qwen3.6-plus\",\"usage\":{\"x_tools\":{\"web_search\":{\"count\":1}}},"
                + "\"output\":[{\"type\":\"reasoning\"}," + searchCalls
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"推荐团油、滴滴加油。\",\"annotations\":[]}]}]}}\n\n";
    }

    /**
     * @param sources 来源 JSON 数组
     * @return 含检索词与来源的搜索记录
     */
    private String searchCall(String sources) {
        return "{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\","
                + "\"query\":\"加油优惠\",\"sources\":" + sources + "}},";
    }
}
