package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation;
import java.util.List;
import java.util.Map;

/**
 * 收集模型回答中的结构化网页引用。
 * 各策略通过 collect 将 url_citation 收集到 URL 索引；不返回 url_citation 的厂商策略
 * 通过 collectSearchSources 采用接口结构化返回的搜索来源。
 * <p>兼容扁平和嵌套注解，不从正文链接猜测引用，不核验网页内容。</p>
 */
final class LlmCitationParser {
    /** 仅提供静态解析方法，不创建工具实例。 */
    private LlmCitationParser() {}

    /**
     * 收集扁平或嵌套的网页引用，并按 URL 去重。
     * @param annotations 回答中的结构化注解
     * @param citations 已收集的引用
     */
    static void collect(Object annotations, Map<String, LLMCitation> citations) {
        // 1. 只接受注解数组，缺失或其他结构不作为引用来源
        if (!(annotations instanceof JSONArray array)) {
            return;
        }
        for (Object value : array) {
            // 2. 仅处理明确的引用类型；Chat 通常嵌套，Responses 通常扁平
            if (!(value instanceof JSONObject annotation) || !"url_citation".equals(annotation.getString("type"))) {
                continue;
            }
            JSONObject citation = annotation.get("url_citation") instanceof JSONObject nested ? nested : annotation;
            // 3. 筛选文本 URL 并收集引用，同一 URL 保留首次资料，位置只接受整数
            if (!(citation.get("url") instanceof String url) || url.isBlank()) {
                continue;
            }
            // 豆包在 summary 中返回摘要，其余协议保留 text；缺失时不补造来源信息。
            citations.putIfAbsent(url, new LLMCitation(
                    citation.get("title") instanceof String title ? title : "", url,
                    citation.get("start_index") instanceof Integer || citation.get("start_index") instanceof Long
                            || citation.get("start_index") instanceof java.math.BigInteger ? citation.getIntValue("start_index") : null,
                    citation.get("end_index") instanceof Integer || citation.get("end_index") instanceof Long
                            || citation.get("end_index") instanceof java.math.BigInteger ? citation.getIntValue("end_index") : null,
                    citation.get("summary") instanceof String summary ? summary
                            : citation.get("text") instanceof String text ? text : ""));
        }
    }

    /**
     * 收集 Responses 搜索调用中的来源网址，供不返回 url_citation 的厂商（千问、智谱）作为引用。
     * <p>来源只有网址，标题、摘要和位置留空；已存在的同一网址保留原资料。</p>
     * @param output Responses 的 output 数组，接口未返回时为 null
     * @param citations 已收集的引用，按 URL 去重
     */
    static void collectSearchSources(JSONArray output, Map<String, LLMCitation> citations) {
        for (Object value : output == null ? List.of() : output) {
            if (!(value instanceof JSONObject outputItem) || !"web_search_call".equals(outputItem.getString("type"))
                    || !(outputItem.get("action") instanceof JSONObject action)
                    || !(action.get("sources") instanceof JSONArray sources)) {
                continue;
            }
            // 只取明确为网址类型的来源，跳过空地址；同一网页被多次搜索到只记一次
            for (Object sourceValue : sources) {
                if (sourceValue instanceof JSONObject source && "url".equals(source.getString("type"))
                        && source.get("url") instanceof String url && !url.isBlank()) {
                    citations.putIfAbsent(url.strip(), new LLMCitation("", url.strip(), null, null, ""));
                }
            }
        }
    }
}
