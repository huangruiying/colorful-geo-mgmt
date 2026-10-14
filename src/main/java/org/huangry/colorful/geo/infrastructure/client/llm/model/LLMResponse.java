package org.huangry.colorful.geo.infrastructure.client.llm.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import java.util.List;

/**
 * 通道解析后的大模型结果，仅承载数据。
 * <p>由通道填充，调用方通过 getter 读取；不承担网络请求、JSON 解析或脱敏处理。</p>
 */
@Getter
@Setter
@NoArgsConstructor
public class LLMResponse {
    /** 模型回答正文。 */
    private String content = "";
    /** 接口返回的结构化引用，未提供时为空列表。 */
    private List<LLMCitation> citations = List.of();
    /** 实际模型，仅用于诊断存储，不扩展原问答 JSON。 */
    @JsonIgnore
    private String modelName;
    /** 接口完整用量 JSON，包含工具用量。 */
    @JsonIgnore
    private String usageInfo;
    /** 接口结束原因或执行状态。 */
    @JsonIgnore
    private String finishReason;
    /** 接口原始响应 JSON。 */
    @JsonIgnore
    private String rawResponse;

    /**
     * 保留正文与引用的构造入口，空引用使用空列表。
     * @param content 模型正文
     * @param citations 结构化引用
     */
    public LLMResponse(String content, List<LLMCitation> citations) {
        this.content = content == null ? "" : content;
        this.citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /**
     * 创建仅含正文的结果。
     * @param content 模型正文
     * @return 无引用的结果
     */
    public static LLMResponse withoutCitations(String content) {
        return new LLMResponse(content, List.of());
    }
}
