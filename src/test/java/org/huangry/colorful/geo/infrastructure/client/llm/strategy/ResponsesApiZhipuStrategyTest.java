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

/** 验证智谱联网请求契约及多次搜索来源合并为引用，不调用外部模型。 */
class ResponsesApiZhipuStrategyTest {
    /** 挂载搜索并流式读取，不发送智谱忽略的 tool_choice。 */
    @Test
    void shouldMountWebSearchWithStreamingWithoutToolChoice() {
        var body = new ResponsesApiZhipuStrategy().buildRequestBody(new LLMQueryRequest("2026年加油优惠App推荐哪些？"), channel());

        assertEquals(List.of(Map.of("type", "web_search")), body.get("tools"));
        assertFalse(body.containsKey("tool_choice"));
        assertEquals(true, body.get("stream"));
        assertEquals("glm-5", body.get("model"));
    }

    /** 多次搜索的来源按出现顺序合并去重为引用。 */
    @Test
    void shouldMergeSourcesFromMultipleSearches() {
        String events = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp-zhipu\",\"status\":\"completed\","
                + "\"model\":\"glm-5\",\"output\":["
                + searchCall("[{\"type\":\"url\",\"url\":\"https://www.xiaoxiongyouhao.com/fprice\"},"
                        + "{\"type\":\"url\",\"url\":\"https://www.autohome.com.cn/oil/110000.html\"}]")
                + searchCall("[{\"type\":\"url\",\"url\":\"https://www.xiaoxiongyouhao.com/fprice\"}]")
                + "{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"北京92号汽油8.61元/升。\","
                + "\"annotations\":[]}]}]}}\n\n";
        var template = new RestTemplate();
        var server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("http://localhost/responses"))
                .andRespond(withSuccess(events, MediaType.TEXT_EVENT_STREAM));
        var strategy = new ResponsesApiZhipuStrategy();
        ReflectionTestUtils.setField(strategy, "llmRestTemplate", template);

        LLMResponse result = strategy.query(new LLMQueryRequest("测试问题"), channel());

        assertEquals("北京92号汽油8.61元/升。", result.getContent());
        assertEquals(List.of(new LLMCitation("", "https://www.xiaoxiongyouhao.com/fprice", null, null, ""),
                new LLMCitation("", "https://www.autohome.com.cn/oil/110000.html", null, null, "")), result.getCitations());
    }

    /** @return 不包含真实密钥的本地测试渠道 */
    private GeoLlmChannelEntity channel() {
        var channel = new GeoLlmChannelEntity();
        channel.setBaseUrl("http://localhost");
        channel.setModelName("glm-5");
        channel.setApiKey("test-only");
        return channel;
    }

    /**
     * @param sources 来源 JSON 数组
     * @return 按智谱实测格式组装的搜索记录，以逗号结尾
     */
    private String searchCall(String sources) {
        return "{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"query\":\"北京油价\","
                + "\"queries\":[\"北京油价\"],\"sources\":" + sources + "}},";
    }
}
