package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.config.LLMProviderProperties;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;
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

    @Override
    public boolean supports(LLMProviderProperties.Provider provider, boolean webSearchEnabled) {
        return provider.getType() == LLMProviderType.MOCK;
    }

    @Override
    public int priority() {
        return 10;
    }

    @Override
    public LLMResponse query(LLMQueryRequest request, LLMProviderProperties.Provider provider, String apiKey) {
        return LLMResponse.withoutCitations("当前配置启用 mock 大模型，未调用真实大模型服务。");
    }
}
