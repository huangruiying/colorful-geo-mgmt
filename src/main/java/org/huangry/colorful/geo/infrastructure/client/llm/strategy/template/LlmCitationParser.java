package org.huangry.colorful.geo.infrastructure.client.llm.strategy.template;

import com.fasterxml.jackson.databind.JsonNode;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation;
import java.util.Map;

/** 两种协议共用的引用注解解析，只接受明确的 url_citation，不猜测正文或检索来源。 */
final class LlmCitationParser {
    private LlmCitationParser() {}

    /**
     * 收集扁平或嵌套的网页引用，并按 URL 去重。
     * @param annotations 回答中的结构化注解
     * @param citations 已收集的引用
     */
    static void collect(JsonNode annotations, Map<String, LLMCitation> citations) {
        if (!annotations.isArray()) return;
        for (JsonNode annotation : annotations) {
            // Chat Completions 使用嵌套对象，Responses 使用扁平注解。
            if (!"url_citation".equals(annotation.path("type").asText())) continue;
            JsonNode citation = annotation.path("url_citation").isObject()
                    ? annotation.path("url_citation") : annotation;
            JsonNode url = citation.path("url");
            if (!url.isTextual() || url.asText().isBlank()) continue;
            citations.putIfAbsent(url.asText(), new LLMCitation(
                    citation.path("title").asText(""), url.asText(),
                    citation.path("start_index").isIntegralNumber() ? citation.path("start_index").asInt() : null,
                    citation.path("end_index").isIntegralNumber() ? citation.path("end_index").asInt() : null,
                    citation.path("text").asText("")));
        }
    }
}
