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

/**
 * 数据库模型路由，query 选择已启用渠道并按 Bean 名调用实际策略。
 * <p>指定渠道和模型同时作为筛选条件，默认按 score 降序、ID 升序选择；
 * 配置无效或调用失败时明确报错，不更换模型、不复制连接配置。</p>
 */
@Component
@Slf4j
public class LLMStrategyRouter {
    /** 渠道配置查询入口，包含当前调用所需的连接与密钥。 */
    @Resource
    private GeoLlmChannelDao channelDao;

    /** Spring 自动收集的策略，键为数据库保存的实际 Bean 名。 */
    @Resource
    private Map<String, LLMStrategy> strategies;

    /**
     * 一次查询定位渠道，校验策略与密钥后执行独立问答。
     * @param request 问题及可选的渠道、模型条件
     * @return 实际策略返回的正文和结构化引用
     * @throws LLMClientException 无符合条件的渠道、策略未注册或远程密钥为空
     */
    public LLMResponse query(LLMQueryRequest request) {
        // 1. 按指定渠道或模型筛选，再按优先级选择唯一配置。
        GeoLlmChannelEntity channel = selectChannel(request);
        // 启用筛选已经由数据库查询完成，沿用普通查询原有策略校验。
        return execute(request, channel);
    }

    /**
     * 按任务已校验的精确渠道调用，不再次按优先级选择其他渠道。
     * @param request 独立问题
     * @param channel 本次诊断选定的渠道及当前凭据
     * @return 模型响应
     */
    public LLMResponse query(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        return execute(request, channel);
    }

    /**
     * 普通查询和精确渠道调用共用策略路由，不复制连接配置。
     * @param request 独立问题
     * @param channel 已选定配置
     * @return 模型响应
     */
    private LLMResponse execute(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        LLMStrategy strategy = strategyFor(channel);
        log.info("模型调用 channel={}, model={}, strategy={}", channel.getChannelCode(), channel.getModelName(), channel.getStrategyBeanName());
        return strategy.query(request, channel);
    }

    /**
     * 校验实际 Bean 与远程密钥，接口地址等协议条件仍由具体策略校验。
     * @param channel 已选定渠道
     * @return 已注册策略
     */
    private LLMStrategy strategyFor(GeoLlmChannelEntity channel) {
        LLMStrategy strategy = strategies.get(channel.getStrategyBeanName());
        if (strategy == null) {
            throw new LLMClientException("渠道配置的策略 Bean 未注册，请修改模型配置");
        }
        if (strategy.protocolType() != LLMProtocolType.MOCK && (StringUtils.isBlank(channel.getApiKey()))) {
            throw new LLMClientException("模型渠道未保存 API 密钥");
        }
        return strategy;
    }

    /**
     * 查询唯一候选；指定条件不匹配时不回退到其他渠道。
     * @param request 渠道与模型筛选条件
     * @return 优先级最高的已启用渠道，同分时取最小 ID
     */
    private GeoLlmChannelEntity selectChannel(LLMQueryRequest request) {
        // 清理选择条件；不处理问题原文，以保留诊断输入。
        String code = normalize(request.channelCode());
        String model = normalize(request.modelName());
        LambdaQueryWrapper<GeoLlmChannelEntity> query = new LambdaQueryWrapper<GeoLlmChannelEntity>()
            .eq(GeoLlmChannelEntity::getEnabled, true)
            .eq(!code.isEmpty(), GeoLlmChannelEntity::getChannelCode, code)
            .eq(!model.isEmpty(), GeoLlmChannelEntity::getModelName, model)
            .orderByDesc(GeoLlmChannelEntity::getScore).orderByAsc(GeoLlmChannelEntity::getId).last("LIMIT 1");
        // 只取排序后的第一条，无匹配项立即阻断调用。
        GeoLlmChannelEntity channel = channelDao.selectOne(query);
        if (channel == null) {
            throw new LLMClientException("没有符合渠道及模型条件的已启用模型配置");
        }
        return channel;
    }

    /**
     * 去除可选筛选值的首尾空白，空值表示不指定条件。
     * @param value 渠道编码或模型标识
     * @return 清理后的条件，null 转为空字符串
     */
    private String normalize(String value) { return value == null ? "" : value.strip(); }
}
