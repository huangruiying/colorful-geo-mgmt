package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 验证 Chat Completions 按实际注解解析引用，不依赖搜索能力标志。 */
class OpenAiStandardLlmStrategyTest {
    /** 普通链接不能伪装为引用；结构化注解按 URL 去重并跳过空链接。 */
    @Test
    void shouldExtractStructuredCitationsOnly() throws Exception {
        var strategy = new OpenAiChatCompletionsApiStrategy();
        var response = strategy.parseResponse(new ObjectMapper().readTree("""
            {"choices":[{"message":{"content":"回答 https://unverified.example","annotations":[
              {"type":"url_citation","url_citation":{"url":"https://source.example","title":"来源","start_index":0,"end_index":2}},
              {"type":"url_citation","url_citation":{"url":"https://source.example"}},
              {"type":"url_citation","url_citation":{"url":""}}
            ]}}]}
            """));
        assertEquals(1, response.citations().size());
        assertEquals("https://source.example", response.citations().get(0).url());
        assertEquals(0, response.citations().get(0).startIndex());
        assertTrue(strategy.parseResponse(new ObjectMapper().readTree(
            "{\"choices\":[{\"message\":{\"content\":\"https://example.com\"}}]}"))
            .citations().isEmpty());
    }
}
