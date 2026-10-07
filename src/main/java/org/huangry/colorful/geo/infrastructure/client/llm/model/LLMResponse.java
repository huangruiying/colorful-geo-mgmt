package org.huangry.colorful.geo.infrastructure.client.llm.model;

import java.util.List;

/**
 * 大模型查询结果。
 *
 * <p>没有结构化网页引用的通道必须返回空 citations，不允许从模型正文猜测或伪造引用。</p>
 *
 * @param content 模型回答正文，null 转为空字符串，不代表内容已经审核
 * @param citations 接口返回的结构化引用，null 转为空列表；列表元素不允许 null
 * @author huangry
 */
public record LLMResponse(String content, List<LLMCitation> citations) {

    /** 归一化空值并保存引用列表的不可变副本，便于调用方统一读取结果。 */
    public LLMResponse {
        content = content == null ? "" : content;
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /**
     * 创建没有网页引用的响应。
     *
     * @param content 模型正文
     * @return 标准响应
     */
    public static LLMResponse withoutCitations(String content) {
        return new LLMResponse(content, List.of());
    }
}
