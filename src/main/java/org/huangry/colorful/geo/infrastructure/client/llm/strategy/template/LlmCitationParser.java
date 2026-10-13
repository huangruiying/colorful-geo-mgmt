package org.huangry.colorful.geo.infrastructure.client.llm.strategy.template;

import com.fasterxml.jackson.databind.JsonNode;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation;
import java.util.Map;

/**
 * 两种协议共用的引用解析工具，collect 将 url_citation 收集到 URL 索引。
 * <p>兼容扁平和嵌套注解，不猜测正文链接或检索来源，不核验网页内容。</p>
 */
final class LlmCitationParser {
    /** 仅提供静态解析方法，不创建工具实例。 */
    private LlmCitationParser() {}

    /**
     * 收集扁平或嵌套的网页引用，并按 URL 去重。
     * @param annotations 回答中的结构化注解
     * @param citations 已收集的引用
     */
    static void collect(JsonNode annotations, Map<String, LLMCitation> citations) {
        if (!annotations.isArray()) {
            return;
        }
        for (JsonNode annotation : annotations) {
            // 仅处理明确的引用类型；Chat 通常嵌套，Responses 通常扁平。
            if (!"url_citation".equals(annotation.path("type").asText())) {
                continue;
            }
            JsonNode citation = annotation.path("url_citation").isObject()
                    ? annotation.path("url_citation") : annotation;
            // 无有效 URL 的注解不入结果，同一 URL 保留首次出现的资料。
            JsonNode url = citation.path("url");
            if (!url.isTextual() || url.asText().isBlank()) {
                continue;
            }
            // 标题和摘要缺失时为空，位置缺失时为 null，不补造来源信息。
            citations.putIfAbsent(url.asText(), new LLMCitation(
                    citation.path("title").asText(""), url.asText(),
                    citation.path("start_index").isIntegralNumber() ? citation.path("start_index").asInt() : null,
                    citation.path("end_index").isIntegralNumber() ? citation.path("end_index").asInt() : null,
                    citation.path("text").asText("")));
        }
    }
}
