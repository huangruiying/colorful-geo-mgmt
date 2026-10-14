package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.alibaba.fastjson.JSON;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证换用 Fastjson 后的回答、引用和失败语义，不调用远程模型。 */
class FastjsonResponseParsingTest {
    /** 首个候选独立解析，嵌套引用去重，非文本 URL 和小数位置不强转。 */
    @Test
    void shouldPreserveChatTextCitationsAndMetadata() {
        CompatibleChatCompletionsApiStrategy strategy = new CompatibleChatCompletionsApiStrategy();
        var result = strategy.parseResponse(JSON.parseObject("""
                {"model":"test-model","usage":{"total_tokens":0,"detail":null},"extra":null,
                 "choices":[{"finish_reason":"stop","message":{"content":"回答","annotations":[
                   {"type":"url_citation","url_citation":{"url":"https://example.invalid","title":"来源","start_index":1,"end_index":1.5}},
                   {"type":"url_citation","url":"https://example.invalid","title":"重复"},
                   {"type":"url_citation","url":123}]}},
                   {"message":{"content":"其他候选"}}]}
                """));
        assertEquals("回答", result.getContent());
        assertEquals("test-model", result.getModelName());
        assertEquals("stop", result.getFinishReason());
        assertEquals(1, result.getCitations().size());
        assertEquals("来源", result.getCitations().get(0).title());
        assertEquals(1, result.getCitations().get(0).startIndex());
        assertNull(result.getCitations().get(0).endIndex());
        assertEquals(0, JSON.parseObject(result.getUsageInfo()).getIntValue("total_tokens"));
        assertTrue(JSON.parseObject(result.getUsageInfo()).containsKey("detail"));
        assertTrue(JSON.parseObject(result.getRawResponse()).containsKey("extra"));
    }

    /** 错误结构不能经 Fastjson 的类型转换被当作有效回答。 */
    @Test
    void shouldRejectMalformedChatTextAndChoices() {
        CompatibleChatCompletionsApiStrategy strategy = new CompatibleChatCompletionsApiStrategy();
        for (String body : new String[]{"{}", "{\"choices\":{}}", "{\"choices\":[null]}",
                "{\"choices\":[{\"message\":{\"content\":123}}]}"}) {
            assertThrows(LLMClientException.class, () -> strategy.parseResponse(JSON.parseObject(body)));
        }
    }

    /** 失败、截断或纯工具响应均拒绝，不能产生成功的空回答。 */
    @Test
    void shouldRejectFailedIncompleteAndToolOnlyResponses() {
        CompatibleResponsesApiStrategy strategy = new CompatibleResponsesApiStrategy();
        for (String body : new String[]{"{\"status\":\"failed\"}", "{\"status\":\"incomplete\"}",
                "{\"error\":{}}", "{\"output\":[{\"type\":\"web_search_call\"}]}",
                "{\"output\":{}}", "{\"output\":[{\"type\":\"message\",\"content\":{}}]}"}) {
            assertThrows(LLMClientException.class, () -> strategy.parseResponse(JSON.parseObject(body)));
        }
    }
}
