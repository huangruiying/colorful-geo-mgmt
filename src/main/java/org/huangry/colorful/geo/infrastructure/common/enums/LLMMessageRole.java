package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 大模型标准会话角色。
 *
 * <p>用于标识请求消息的身份，通过 protocolValue() 转换为远程协议角色值。</p>
 *
 * @author huangry
 */
public enum LLMMessageRole {

    /** 定义模型行为边界和上下文规则的系统消息。 */
    SYSTEM,

    /** 由业务调用方提交的查询消息。 */
    USER,

    /** 用于续接多轮对话的历史模型回答。 */
    ASSISTANT;

    /**
     * 获取 OpenAI-compatible 协议使用的小写角色值。
     *
     * @return 协议角色值
     */
    public String protocolValue() {
        return name().toLowerCase();
    }
}
