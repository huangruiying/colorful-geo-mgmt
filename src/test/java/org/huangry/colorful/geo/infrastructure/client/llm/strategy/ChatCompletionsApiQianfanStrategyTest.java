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

/** 验证文心智能搜索请求契约及网页来源作为引用的解析，不调用外部模型。 */
class ChatCompletionsApiQianfanStrategyTest {
    /** 只检索网页来源，保留父类的单轮问题与生成参数。 */
    @Test
    void shouldRequestBaiduWebSearchOnly() {
        var channel = channel();
        channel.setTemperature(0.2);
        channel.setMaxTokens(2000);
        var body = new ChatCompletionsApiQianfanStrategy().buildRequestBody(new LLMQueryRequest("2026年加油优惠App推荐哪些？"), channel);

        assertEquals("baidu_search_v2", body.get("search_source"));
        assertEquals(List.of(Map.of("type", "web", "top_k", 10)), body.get("resource_type_filter"));
        assertEquals("ernie-5.0", body.get("model"));
        assertEquals(List.of(Map.of("role", "user", "content", "2026年加油优惠App推荐哪些？")), body.get("messages"));
        assertEquals(0.2, body.get("temperature"));
        assertEquals(2000, body.get("max_tokens"));
    }

    /** 网页来源保留标题并按网址去重，非网页类型与空地址跳过，正文角标不参与解析。 */
    @Test
    void shouldUseWebReferencesAsCitations() {
        String response = "{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
                + "\"content\":\"推荐易捷加油^[1]^、团油^[2]^。\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"total_tokens\":9},\"references\":["
                + "{\"id\":\"1\",\"type\":\"web\",\"title\":\"易捷加油优惠\",\"url\":\"https://k.sina.com.cn/article_1.html\",\"content\":\"长正文\"},"
                + "{\"id\":\"2\",\"type\":\"web\",\"title\":\"团油评测\",\"url\":\"https://www.pcauto.com.cn/ask/1450408.html\"},"
                + "{\"id\":\"3\",\"type\":\"web\",\"title\":\"重复\",\"url\":\"https://k.sina.com.cn/article_1.html\"},"
                + "{\"id\":\"4\",\"type\":\"video\",\"title\":\"视频\",\"url\":\"https://example.invalid/video\"},"
                + "{\"id\":\"5\",\"type\":\"web\",\"title\":\"无地址\",\"url\":\"\"}]}";
        var template = new RestTemplate();
        var server = MockRestServiceServer.bindTo(template).build();
        server.expect(requestTo("http://localhost/chat/completions"))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        var strategy = new ChatCompletionsApiQianfanStrategy();
        ReflectionTestUtils.setField(strategy, "llmRestTemplate", template);

        LLMResponse result = strategy.query(new LLMQueryRequest("测试问题"), channel());

        assertEquals("推荐易捷加油^[1]^、团油^[2]^。", result.getContent());
        assertEquals(List.of(new LLMCitation("易捷加油优惠", "https://k.sina.com.cn/article_1.html", null, null, ""),
                new LLMCitation("团油评测", "https://www.pcauto.com.cn/ask/1450408.html", null, null, "")), result.getCitations());
        assertNull(result.getModelName());
        assertEquals("stop", result.getFinishReason());
    }

    /** 未返回来源时引用为空，回答仍成功。 */
    @Test
    void shouldLeaveCitationsEmptyWithoutReferences() {
        var strategy = new ChatCompletionsApiQianfanStrategy();
        LLMResponse result = strategy.parseResponse(com.alibaba.fastjson.JSON.parseObject(
                "{\"choices\":[{\"message\":{\"content\":\"回答\"},\"finish_reason\":\"stop\"}]}"));

        assertEquals("回答", result.getContent());
        assertTrue(result.getCitations().isEmpty());
    }

    /** @return 指向本地地址、不包含真实密钥的测试渠道 */
    private GeoLlmChannelEntity channel() {
        var channel = new GeoLlmChannelEntity();
        channel.setBaseUrl("http://localhost");
        channel.setModelName("ernie-5.0");
        channel.setApiKey("test-only");
        return channel;
    }
}
