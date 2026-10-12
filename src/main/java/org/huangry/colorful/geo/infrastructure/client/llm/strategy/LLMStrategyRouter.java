package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import jakarta.annotation.PostConstruct;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMConnectionConfig;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.GeoLlmChannelDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Component;
import java.util.EnumMap;
import java.util.Map;
import java.util.List;

/** 数据库模型路由；按指定条件与 score 选择渠道，不在失败后自动切换模型。 */
@Component
@Slf4j
public class LLMStrategyRouter {
    private final Map<LLMProtocolType, LLMStrategy> strategies = new EnumMap<>(LLMProtocolType.class);
    @Resource
    private GeoLlmChannelDao channelDao;

    @Resource
    private List<LLMStrategy> candidates;

    /** 注册协议策略；同一类型重复注册时立即拒绝启动。 */
    @PostConstruct
    void registerStrategies() {
        for (LLMStrategy strategy : candidates) {
            if (strategies.putIfAbsent(strategy.llmProviderType(), strategy) != null)
                throw new IllegalArgumentException("模型协议策略重复: " + strategy.llmProviderType());
        }
    }

    /** 定位渠道、适配协议并调用，保留本次数据库配置快照。 */
    public LLMResponse query(LLMQueryRequest request) {
        // 1. 按指定渠道或模型筛选，再按优先级选择唯一配置。
        GeoLlmChannelEntity channel = selectChannel(request);
        LLMConnectionConfig provider = toProvider(channel);
        // 2. 按选中渠道的协议匹配策略，由策略决定请求方式。
        LLMStrategy strategy = selectStrategy(provider);
        String apiKey = provider.getType() == LLMProtocolType.MOCK ? "" : resolveApiKey(channel);
        // 3. 只记录实际渠道与模型，不记录问题、回答或凭据。
        log.info("模型调用 channel={}, model={}, protocol={}", channel.getChannelCode(), channel.getModelName(), channel.getProtocolType());
        return strategy.query(request, provider, apiKey);
    }

    /** 按指定渠道和模型筛选，再按 score 降序、ID 升序选择。 */
    private GeoLlmChannelEntity selectChannel(LLMQueryRequest request) {
        String code = normalize(request.channelCode());
        String model = normalize(request.modelName());
        LambdaQueryWrapper<GeoLlmChannelEntity> query = new LambdaQueryWrapper<GeoLlmChannelEntity>()
            .eq(GeoLlmChannelEntity::getEnabled, true)
            .eq(!code.isEmpty(), GeoLlmChannelEntity::getChannelCode, code)
            .eq(!model.isEmpty(), GeoLlmChannelEntity::getModelName, model)
            .orderByDesc(GeoLlmChannelEntity::getScore).orderByAsc(GeoLlmChannelEntity::getId).last("LIMIT 1");
        List<GeoLlmChannelEntity> channels = channelDao.selectList(query);
        if (channels.isEmpty()) throw new LLMClientException("没有符合渠道及模型条件的已启用模型配置");
        return channels.get(0);
    }

    /** 将渠道配置转换为现有策略参数，继续复用协议客户端。 */
    private LLMConnectionConfig toProvider(GeoLlmChannelEntity channel) {
        LLMConnectionConfig config = new LLMConnectionConfig();
        config.setType(channel.getProtocolType());
        config.setProviderCode(channel.getProviderCode());
        config.setBaseUrl(channel.getBaseUrl());
        config.setModel(channel.getModelName());
        config.setTemperature(channel.getTemperature());
        config.setMaxTokens(channel.getMaxTokens());
        config.setTimeoutSeconds(channel.getTimeoutSeconds());
        config.setSslVerify(channel.getSslVerify());
        return config;
    }

    /** 按渠道协议唯一匹配策略。 */
    private LLMStrategy selectStrategy(LLMConnectionConfig provider) {
        LLMStrategy strategy = strategies.get(provider.getType());
        if (strategy == null) throw new LLMClientException("没有可处理当前模型协议的策略");
        return strategy;
    }

    /** 仅从选中数据库渠道读取凭据，空密钥直接拒绝远程调用。 */
    private String resolveApiKey(GeoLlmChannelEntity channel) {
        String key = normalize(channel.getApiKey());
        if (key.isEmpty()) throw new LLMClientException("模型渠道未保存 API 密钥");
        return key;
    }

    /** 清理可选选择条件与密钥引用。 */
    private String normalize(String value) { return value == null ? "" : value.strip(); }
}
