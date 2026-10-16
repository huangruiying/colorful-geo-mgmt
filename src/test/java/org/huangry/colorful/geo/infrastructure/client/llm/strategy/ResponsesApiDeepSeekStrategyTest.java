package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.alibaba.fastjson.JSON;
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

/** 验证 DeepSeek 联网请求契约及已打开网页作为引用的解析，不调用外部模型。 */
class ResponsesApiDeepSeekStrategyTest {
    /** 强制服务端搜索且不携带豆包专用来源，保留模型与单次问题配置。 */
    @Test
    void shouldRequireWebSearchWithoutDoubaoSources() {
        var channel = channel();
        channel.setMaxTokens(2000);
        var body = new ResponsesApiDeepSeekStrategy().buildRequestBody(new LLMQueryRequest("2026年加油优惠App推荐哪些？"), channel);

        assertEquals(List.of(Map.of("type", "web_search")), body.get("tools"));
        assertEquals("required", body.get("tool_choice"));
        assertEquals(true, body.get("stream"));
        assertEquals("deepseek-v4-pro", body.get("model"));
        assertEquals(2000, body.get("max_output_tokens"));
        assertFalse(body.containsKey("temperature"));
        assertEquals(false, body.get("store"));
        assertEquals(List.of(Map.of("role", "user", "content", List.of(Map.of("type", "input_text", "text", "2026年加油优惠App推荐哪些？")))), body.get("input"));
    }

    /** 已打开网页去掉调用标识后作为引用，同一网页去重，检索词不计入，正文不混入工具记录。 */
    @Test
    void shouldUseOpenedPagesAsCitations() {
        LLMResponse result = query(completed(searchCall() + openPage("https://www.liqucn.com/zj-2869080#ws_call_id=call_02")
                + openPage("https://www.liqucn.com/zj-2869080#ws_call_id=call_05")
                + openPage("http://www.investorscn.com/2026/07/16/134082/#ws_call_id=call_03"), "[]"));

        assertEquals("推荐团油、易加油。", result.getContent());
        assertEquals(List.of(new LLMCitation("", "https://www.liqucn.com/zj-2869080", null, null, ""),
                new LLMCitation("", "http://www.investorscn.com/2026/07/16/134082/", null, null, "")), result.getCitations());
        assertEquals("completed", result.getFinishReason());
        assertEquals("deepseek-v4-pro", result.getModelName());
        assertEquals(2, JSON.parseObject(result.getUsageInfo()).getJSONObject("input_tokens_details").getIntValue("cached_tokens"));
    }

    /** 只检索未打开网页时没有可证明已读的来源，引用为空而回答仍成功。 */
    @Test
    void shouldLeaveCitationsEmptyWhenOnlySearched() {
        LLMResponse result = query(completed(searchCall(), "[]"));

        assertEquals("推荐团油、易加油。", result.getContent());
        assertTrue(result.getCitations().isEmpty());
    }

    /** 文本注解已有同一网页时保留注解中的标题与摘要，已打开网页只追加新地址。 */
    @Test
    void shouldKeepAnnotationCitationBeforeOpenedPage() {
        String annotations = "[{\"type\":\"url_citation\",\"url\":\"https://www.liqucn.com/zj-2869080\","
                + "\"title\":\"加油App评测\",\"summary\":\"摘要\"}]";
        LLMResponse result = query(completed(openPage("https://www.liqucn.com/zj-2869080#ws_call_id=call_02")
                + openPage("https://post.smzdm.com/talk/p/aggd7e76/#ws_call_id=call_04"), annotations));

        assertEquals(List.of(new LLMCitation("加油App评测", "https://www.liqucn.com/zj-2869080", null, null, "摘要"),
                new LLMCitation("", "https://post.smzdm.com/talk/p/aggd7e76/", null, null, "")), result.getCitations());
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
        var strategy = new ResponsesApiDeepSeekStrategy();
        ReflectionTestUtils.setField(strategy, "llmRestTemplate", template);
        LLMResponse result = strategy.query(new LLMQueryRequest("测试问题"), channel());
        server.verify();
        return result;
    }

    /** @return 不包含真实密钥的本地测试渠道 */
    private GeoLlmChannelEntity channel() {
        var channel = new GeoLlmChannelEntity();
        channel.setBaseUrl("http://localhost");
        channel.setModelName("deepseek-v4-pro");
        channel.setApiKey("test-only");
        return channel;
    }

    /**
     * 按 DeepSeek 实测结构组装完成事件：推理、工具调用记录后输出正文。
     * @param searchCalls 已序列化的 web_search_call 项，每项以逗号结尾
     * @param annotations 正文注解 JSON 数组
     * @return 含进度事件与完成事件的 SSE 文本
     */
    private String completed(String searchCalls, String annotations) {
        return "data: {\"type\":\"response.web_search_call.completed\"}\n\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp-deepseek\",\"status\":\"completed\","
                + "\"model\":\"deepseek-v4-pro\",\"usage\":{\"input_tokens\":5,\"input_tokens_details\":{\"cached_tokens\":2}},"
                + "\"output\":[{\"type\":\"reasoning\",\"content\":[{\"type\":\"reasoning_text\",\"text\":\"需要搜索\"}]},"
                + searchCalls
                + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\","
                + "\"text\":\"推荐团油、易加油。\",\"annotations\":" + annotations + "}]}]}}\n\n";
    }

    /** @return 只含检索词的搜索记录 */
    private String searchCall() {
        return "{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"search\",\"queries\":[\"加油优惠App\"]}},";
    }

    /**
     * @param url 接口返回的已打开网页地址，含调用标识
     * @return 打开网页记录
     */
    private String openPage(String url) {
        return "{\"type\":\"web_search_call\",\"status\":\"completed\",\"action\":{\"type\":\"open_page\",\"url\":\"" + url + "\"}},";
    }
}
