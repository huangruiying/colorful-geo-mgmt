package org.huangry.colorful.geo.infrastructure.client.llm.model;

import org.huangry.colorful.geo.infrastructure.common.enums.LLMMessageRole;
/**
 * 大模型会话中的一条消息。
 *
 * <p>第一版仅支持 system、user、assistant 三种标准角色。</p>
 *
 * @param role 消息角色，必填，用于区分系统规则、用户输入和历史回答
 * @param content 消息正文，必填且不能仅包含空白
 * @author huangry
 */
public record LLMMessage(LLMMessageRole role, String content) {

    /** 校验消息身份和正文，避免无效消息进入模型请求。 */
    public LLMMessage {
        if (role == null) {
            throw new IllegalArgumentException("大模型消息角色不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("大模型消息内容不能为空");
        }
    }
}
