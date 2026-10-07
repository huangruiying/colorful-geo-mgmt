package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.config.LLMProviderProperties;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;

/**
 * 大模型调用通道差异策略。
 *
 * <p>策略只处理协议适配，不承担供应商选择、密钥解析或业务领域编排。</p>
 *
 * @author huangry
 */
public interface LLMStrategy {

    /**
     * 判断策略是否可处理当前供应商与能力请求。
     *
     * @param provider 供应商配置
     * @param webSearchEnabled 是否请求联网搜索
     * @return 是否可处理
     */
    boolean supports(LLMProviderProperties.Provider provider, boolean webSearchEnabled);

    /**
     * 数值越小优先级越高，用于优先选择联网搜索等特定通道。
     *
     * @return 策略优先级
     */
    int priority();

    /**
     * 调用已匹配的模型通道。
     *
     * @param request 通用查询请求
     * @param provider 供应商配置
     * @param apiKey 已解析的密钥；mock 策略可忽略
     * @return 标准查询结果
     */
    LLMResponse query(LLMQueryRequest request, LLMProviderProperties.Provider provider, String apiKey);
}
