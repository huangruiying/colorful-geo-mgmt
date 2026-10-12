package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMConnectionConfig;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;
import org.springframework.stereotype.Component;

/**
 * 本地 mock 大模型策略。
 *
 * <p>用于本地开发和未配置真实密钥的场景，不会发起任何远程请求。</p>
 *
 * @author huangry
 */
@Component
public class MockLlmStrategy implements LLMStrategy {

    /** 本地模拟协议不发起远程请求。 */
    @Override
    public LLMProtocolType llmProviderType() { return LLMProtocolType.MOCK; }

    @Override
    public LLMResponse query(LLMQueryRequest request, LLMConnectionConfig provider, String apiKey) {
        return LLMResponse.withoutCitations("当前配置启用 mock 大模型，未调用真实大模型服务。");
    }
}
