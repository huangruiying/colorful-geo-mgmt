package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.LLMStrategy;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.GeoLlmChannelDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Component;
import java.util.Map;

/** 数据库模型路由；按指定条件与 score 选择渠道，不在失败后自动切换模型。 */
@Component
@Slf4j
public class LLMStrategyRouter {
    @Resource
    private GeoLlmChannelDao channelDao;

    @Resource
    private Map<String, LLMStrategy> strategies;

    /** 一次查询定位渠道，按 Bean 名调用，不复制配置或改用其他模型。 */
    public LLMResponse query(LLMQueryRequest request) {
        // 1. 按指定渠道或模型筛选，再按优先级选择唯一配置。
        GeoLlmChannelEntity channel = selectChannel(request);
        // 2. 按数据库保存的 Bean 名直接获取 Spring 实例，由策略决定请求方式。
        LLMStrategy strategy = strategies.get(channel.getStrategyBeanName());
        if (strategy == null) throw new LLMClientException("渠道配置的策略 Bean 未注册，请修改模型配置");
        if (strategy.protocolType() != LLMProtocolType.MOCK && (StringUtils.isBlank(channel.getApiKey())))
            throw new LLMClientException("模型渠道未保存 API 密钥");
        // 3. 只记录实际渠道与模型，不记录问题、回答或凭据。
        log.info("模型调用 channel={}, model={}, strategy={}", channel.getChannelCode(), channel.getModelName(), channel.getStrategyBeanName());
        return strategy.query(request, channel);
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
        GeoLlmChannelEntity channel = channelDao.selectOne(query);
        if (channel == null) throw new LLMClientException("没有符合渠道及模型条件的已启用模型配置");
        return channel;
    }

    /** 清理可选渠道和模型选择条件，不改写问题原文。 */
    private String normalize(String value) { return value == null ? "" : value.strip(); }
}
