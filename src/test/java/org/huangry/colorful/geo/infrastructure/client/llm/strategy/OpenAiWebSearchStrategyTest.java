package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OpenAI 网页引用解析单元测试。
 *
 * @author huangry
 */
class OpenAiWebSearchStrategyTest {

    @Test
    void 应只返回结构化网页引用并按链接去重() throws Exception {
        String responseJson = """
                {
                  "output": [{
                    "type": "message",
                    "content": [{
                      "type": "output_text",
                      "text": "第一段回答。",
                      "annotations": [
                        {"type":"url_citation","title":"OpenAI","url":"https://openai.com","start_index":0,"end_index":4},
                        {"type":"url_citation","title":"OpenAI","url":"https://openai.com","start_index":5,"end_index":9},
                        {"type":"file_citation","title":"不应返回"}
                      ]
                    }]
                  }]
                }
                """;
        OpenAiWebSearchStrategy strategy = new OpenAiWebSearchStrategy();
        LLMResponse response = strategy.parseResponse(new ObjectMapper().readTree(responseJson));

        assertEquals("第一段回答。", response.content());
        assertEquals(1, response.citations().size());
        assertEquals("OpenAI", response.citations().get(0).title());
        assertEquals("https://openai.com", response.citations().get(0).url());
        assertEquals(0, response.citations().get(0).startIndex());
        assertEquals(4, response.citations().get(0).endIndex());
    }
}
