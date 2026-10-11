package org.huangry.colorful.geo.infrastructure.client.llm.model;

import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;

/**
 * 独立单次大模型查询；不携带历史回答，重复采样由调用方多次发起。
 *
 * @param prompt 本次问题，必填且不能仅包含空白
 * @param channelCode 指定渠道编码，空值时按模型及优先级选择, {@linkplain GeoLlmChannelEntity#getChannelCode()}
 * @param modelName 指定模型标识，空值时使用选中渠道的模型
 * @author huangry
 */
public record LLMQueryRequest(String prompt, String channelCode, String modelName) {
    /** 单次问题默认按数据库优先级选择渠道。 */
    public LLMQueryRequest(String prompt) {
        this(prompt, null, null);
    }

    /** 拒绝无效问题，保留原文以保证重复采样输入一致。 */
    public LLMQueryRequest {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("大模型查询问题不能为空");
        }
    }
}
