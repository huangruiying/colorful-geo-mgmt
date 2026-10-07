package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.config.LLMProviderProperties;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProviderType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 大模型策略路由器。
 *
 * <p>第一版只路由 enabledLlmProvider 指定的一个供应商；不承担多模型并发与结果聚合。</p>
 *
 * @author huangry
 */
@Component
public class LLMStrategyRouter {

    private final LLMProviderProperties properties;

    private final List<LLMStrategy> strategies;

    public LLMStrategyRouter(LLMProviderProperties properties, List<LLMStrategy> strategies) {
        this.properties = properties;
        this.strategies = strategies;
    }

    /**
     * 选择当前启用供应商的匹配策略并完成调用。
     *
     * @param request 通用查询请求
     * @return 标准查询结果
     */
    public LLMResponse query(LLMQueryRequest request) {
        String providerName = properties.getEnabledLlmProvider();
        LLMProviderProperties.Provider provider = findEnabledProvider(providerName);
        LLMStrategy strategy = selectStrategy(provider, request.webSearchEnabled());

        // provider=mock 不需要密钥；其他通道必须在远程调用前完成凭据校验。
        String apiKey = provider.getType() == LLMProviderType.MOCK ? "" : resolveApiKey(provider, providerName);
        return strategy.query(request, provider, apiKey);
    }

    /**
     * 返回当前启用供应商的名称，供调用日志和后续业务记录使用。
     *
     * @return 已启用供应商名称
     */
    public String getEnabledProviderName() {
        return properties.getEnabledLlmProvider();
    }

    /**
     * 定位 enabled_llm_provider 指定的供应商。
     *
     * <p>配置错误时直接失败，避免悄悄切换到其他模型导致业务结果不可追踪。</p>
     *
     * @param providerName 启用的供应商名称
     * @return 对应供应商配置
     */
    private LLMProviderProperties.Provider findEnabledProvider(String providerName) {
        Map<String, LLMProviderProperties.Provider> providerMap = properties.getProviders();
        if (providerName == null || providerName.isBlank() || providerMap == null || !providerMap.containsKey(providerName)) {
            throw new LLMClientException("enabled_llm_provider 未匹配到已配置的大模型供应商: " + providerName);
        }
        return providerMap.get(providerName);
    }

    /**
     * 按能力匹配与优先级选择协议策略。
     *
     * @param provider 当前供应商配置
     * @param webSearchEnabled 是否请求联网搜索
     * @return 优先级最高的可用策略
     */
    private LLMStrategy selectStrategy(LLMProviderProperties.Provider provider, boolean webSearchEnabled) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(provider, webSearchEnabled))
                .min(Comparator.comparingInt(LLMStrategy::priority))
                .orElseThrow(() -> new LLMClientException("没有可处理当前大模型供应商的调用策略: " + provider.getType()));
    }

    /**
     * 解析远程供应商的访问密钥。
     *
     * <p>环境变量优先于配置文件，避免生产密钥被持久化到仓库或部署包中。</p>
     *
     * @param provider 当前供应商配置
     * @param providerName 当前供应商名称
     * @return 非空访问密钥
     */
    private String resolveApiKey(LLMProviderProperties.Provider provider, String providerName) {
        String envApiKey = readEnvironmentApiKey(provider.getApiKeyEnv());
        if (!envApiKey.isBlank()) {
            return envApiKey;
        }
        if (provider.getApiKey() != null && !provider.getApiKey().isBlank()) {
            return provider.getApiKey().trim();
        }
        throw new LLMClientException("大模型供应商未配置 API Key: " + providerName);
    }

    /**
     * 从环境变量读取访问密钥。
     *
     * @param environmentName 环境变量名称
     * @return 已清理的密钥；变量未配置时为空字符串
     */
    private String readEnvironmentApiKey(String environmentName) {
        if (environmentName == null || environmentName.isBlank()) {
            return "";
        }
        String apiKey = System.getenv(environmentName.trim());
        return apiKey == null ? "" : apiKey.trim();
    }
}
