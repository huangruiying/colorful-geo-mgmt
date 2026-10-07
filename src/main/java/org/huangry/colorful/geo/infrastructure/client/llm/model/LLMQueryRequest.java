package org.huangry.colorful.geo.infrastructure.client.llm.model;

import org.huangry.colorful.geo.infrastructure.common.enums.LLMMessageRole;

import java.util.List;

/**
 * 大模型查询请求。
 *
 * <p>网页搜索用于检索外部信息（如最新资料、数据或官方文档）辅助回答，引用是接口可能返回的来源信息。
 * 搜索开关表示能力请求，不强制模型执行搜索，也不保证返回引用或来源正确；通道不支持时返回普通模型结果。</p>
 *
 * @param messages 按会话顺序排列的消息，列表必填且非空，元素不允许 null
 * @param webSearchEnabled 是否请求启用网页搜索；与调用远程大模型接口所需的网络请求不同
 * @author huangry
 */
public record LLMQueryRequest(List<LLMMessage> messages, boolean webSearchEnabled) {

    /** 校验至少包含一条消息，并保存不可变副本以固定本次请求的消息顺序。 */
    public LLMQueryRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("大模型查询消息不能为空");
        }
        messages = List.copyOf(messages);
    }

    /**
     * 创建默认不启用网页搜索的单轮用户查询。
     *
     * @param userMessage 用户问题
     * @return 单轮查询请求
     */
    public static LLMQueryRequest userMessage(String userMessage) {
        return new LLMQueryRequest(List.of(new LLMMessage(LLMMessageRole.USER, userMessage)), false);
    }
}
