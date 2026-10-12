package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMConnectionConfig;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;

/**
 * 大模型调用通道差异策略。
 *
 * <p>策略只处理协议适配，不承担供应商选择、密钥解析或业务领域编排。</p>
 *
 * @author huangry
 */
public interface LLMStrategy {

    /** 声明策略唯一处理的协议类型；搜索能力由枚举统一维护。 */
    LLMProtocolType llmProviderType();

    /**
     * 调用已匹配的模型通道。
     *
     * @param request 通用查询请求
     * @param provider 供应商配置
     * @param apiKey 已解析的密钥；mock 策略可忽略
     * @return 标准查询结果
     */
    LLMResponse query(LLMQueryRequest request, LLMConnectionConfig provider, String apiKey);
}
