package org.huangry.colorful.geo.infrastructure.client.llm.strategy.template;

import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
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

    /** 策略使用的接口协议，仅供校验和展示。 */
    LLMProtocolType protocolType();

    /**
     * 调用已匹配的模型通道。
     *
     * @param request 通用查询请求
     * @param channel 供应商配置
     * @return 标准查询结果
     */
    LLMResponse query(LLMQueryRequest request, GeoLlmChannelEntity channel);
}
