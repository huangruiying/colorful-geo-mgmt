package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.alibaba.fastjson.JSON;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证豆包完整响应映射到通用结果，使用本地报文，不核验新闻或调用外部模型。 */
class DoubaoResponseParsingTest {
    /** 只提取最终回答和引用，保留搜索用量及原始厂商字段。 */
    @Test
    void shouldMapDoubaoResponseToCommonResult() throws Exception {
        var json = JSON.parseObject("""
                {"model":"doubao-seed-2-1-pro-260915","status":"completed","output":[
                  {"type":"reasoning","summary":[{"type":"summary_text","text":"思考内容"}]},
                  {"type":"web_search_call","action":{"query":"搜索词"}},
                  {"type":"message","content":[{"type":"output_text","text":"最终回答","annotations":[
                    {"type":"url_citation","title":"来源标题","url":"https://example.com/news",
                     "site_name":"示例网站","publish_time":"2026-09-04T10:24:00+08:00","summary":"来源摘要"}]}]}],
                 "usage":{"input_tokens":3666,"output_tokens":1610,"total_tokens":5276,
                  "tool_usage":{"web_search":1},"tool_usage_details":{"web_search":{"doubao":1}}}}
                """);
        ResponsesApiDoubaoStrategy strategy = new ResponsesApiDoubaoStrategy();
        var result = strategy.parseResponse(json);

        assertEquals("最终回答", result.getContent());
        assertEquals("doubao-seed-2-1-pro-260915", result.getModelName());
        assertEquals("completed", result.getFinishReason());
        assertEquals(1, result.getCitations().size());
        var citation = result.getCitations().get(0);
        assertEquals("来源标题", citation.title());
        assertEquals("https://example.com/news", citation.url());
        assertEquals("来源摘要", citation.snippet());
        assertNull(citation.startIndex());
        assertNull(citation.endIndex());
        assertEquals(json.get("usage"), JSON.parseObject(result.getUsageInfo()));
        assertEquals(json, JSON.parseObject(result.getRawResponse()));
    }

    /** 兼容原 text 摘要，同时不从搜索调用伪造回答引用。 */
    @Test
    void shouldRetainTextCitationWhenSummaryIsMissing() throws Exception {
        var json = JSON.parseObject("""
                {"status":"completed","output":[{"type":"message","content":[{"type":"output_text",
                "text":"回答","annotations":[{"type":"url_citation","url":"https://example.com","text":"原摘要"}]}]}]}
                """);
        ResponsesApiDoubaoStrategy strategy = new ResponsesApiDoubaoStrategy();
        assertEquals("原摘要", strategy.parseResponse(json).getCitations().get(0).snippet());
    }
}
