package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMMessageRole;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderName;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;
import org.huangry.colorful.geo.infrastructure.client.llm.config.LLMProviderProperties;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 大模型策略路由器单元测试。
 *
 * @author huangry
 */
class LLMStrategyRouterTest {

    @Test
    void 应使用启用供应商对应的最高优先级策略() {
        LLMProviderProperties properties = createProperties(LLMProviderName.CUSTOM.configurationName(), createCompatibleProvider(false));
        RecordingStrategy commonStrategy = new RecordingStrategy(false, 30, "通用回答");
        RecordingStrategy preferredStrategy = new RecordingStrategy(false, 20, "优先回答");
        LLMStrategyRouter router = new LLMStrategyRouter(properties, List.of(commonStrategy, preferredStrategy));

        LLMResponse response = router.query(LLMQueryRequest.userMessage("测试问题"));

        assertEquals("优先回答", response.content());
        assertEquals(0, commonStrategy.queryCount);
        assertEquals(1, preferredStrategy.queryCount);
        assertEquals("test-api-key", preferredStrategy.receivedApiKey);
    }

    @Test
    void 联网搜索请求应优先选择联网策略() {
        LLMProviderProperties properties = createProperties(LLMProviderName.OPENAI.configurationName(), createCompatibleProvider(true));
        RecordingStrategy commonStrategy = new RecordingStrategy(false, 30, "普通回答");
        RecordingStrategy webSearchStrategy = new RecordingStrategy(true, 20, "联网回答");
        LLMStrategyRouter router = new LLMStrategyRouter(properties, List.of(commonStrategy, webSearchStrategy));

        LLMResponse response = router.query(new LLMQueryRequest(
                List.of(new org.huangry.colorful.geo.infrastructure.client.llm.model.LLMMessage(LLMMessageRole.USER, "今日新闻")), true));

        assertEquals("联网回答", response.content());
        assertEquals(0, commonStrategy.queryCount);
        assertEquals(1, webSearchStrategy.queryCount);
    }

    @Test
    void 未配置启用供应商时应拒绝调用() {
        LLMProviderProperties properties = createProperties("missing", createCompatibleProvider(false));
        LLMStrategyRouter router = new LLMStrategyRouter(properties, List.of(new RecordingStrategy(false, 30, "不会调用")));

        LLMClientException exception = assertThrows(LLMClientException.class,
                () -> router.query(LLMQueryRequest.userMessage("测试问题")));

        assertEquals("enabled_llm_provider 未匹配到已配置的大模型供应商: missing", exception.getMessage());
    }

    private LLMProviderProperties createProperties(String enabledProvider, LLMProviderProperties.Provider provider) {
        LLMProviderProperties properties = new LLMProviderProperties();
        properties.setEnabledLlmProvider(enabledProvider);
        properties.setProviders(Map.of(enabledProvider.equals("missing") ? "custom" : enabledProvider, provider));
        return properties;
    }

    private LLMProviderProperties.Provider createCompatibleProvider(boolean webSearchSupported) {
        LLMProviderProperties.Provider provider = new LLMProviderProperties.Provider();
        provider.setType(LLMProviderType.OPENAI_COMPATIBLE);
        provider.setApiKey("test-api-key");
        provider.setWebSearchSupported(webSearchSupported);
        return provider;
    }

    private static class RecordingStrategy implements LLMStrategy {

        private final boolean webSearchStrategy;

        private final int priority;

        private final String responseContent;

        private int queryCount;

        private String receivedApiKey;

        private RecordingStrategy(boolean webSearchStrategy, int priority, String responseContent) {
            this.webSearchStrategy = webSearchStrategy;
            this.priority = priority;
            this.responseContent = responseContent;
        }

        @Override
        public boolean supports(LLMProviderProperties.Provider provider, boolean webSearchEnabled) {
            return webSearchEnabled == webSearchStrategy;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public LLMResponse query(LLMQueryRequest request, LLMProviderProperties.Provider provider, String apiKey) {
            queryCount++;
            receivedApiKey = apiKey;
            return LLMResponse.withoutCitations(responseContent);
        }
    }
}
