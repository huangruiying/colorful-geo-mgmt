package org.huangry.colorful.geo.infrastructure.client.llm.model;

import org.huangry.colorful.geo.infrastructure.common.enums.LLMMessageRole;

import java.util.List;

/**
 * 大模型查询请求。
 *
 * <p>网页搜索用于检索外部信息（如最新资料、数据或官方文档）辅助回答，引用是接口可能返回的来源信息。
 * 是否提供搜索工具由渠道协议决定，不保证模型实际执行搜索或返回引用。</p>
 *
 * @param messages 按会话顺序排列的消息，列表必填且非空，元素不允许 null
 * @param channelCode 指定渠道编码，空值时按模型及优先级选择
 * @param modelName 指定模型标识，空值时使用选中渠道的模型
 * @author huangry
 */
public record LLMQueryRequest(List<LLMMessage> messages, String channelCode, String modelName) {

    /** 保留现有消息调用方式，默认按数据库优先级选择渠道。 */
    public LLMQueryRequest(List<LLMMessage> messages) {
        this(messages, null, null);
    }

    /** 校验至少包含一条消息，并保存不可变副本以固定本次请求的消息顺序。 */
    public LLMQueryRequest {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("大模型查询消息不能为空");
        }
        messages = List.copyOf(messages);
    }

    /**
     * 创建按渠道协议自动选择请求方式的单轮用户查询。
     *
     * @param userMessage 用户问题
     * @return 单轮查询请求
     */
    public static LLMQueryRequest userMessage(String userMessage) {
        return new LLMQueryRequest(List.of(new LLMMessage(LLMMessageRole.USER, userMessage)));
    }
}
