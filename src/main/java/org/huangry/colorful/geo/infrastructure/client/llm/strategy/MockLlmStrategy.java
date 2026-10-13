package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.LLMStrategy;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.springframework.stereotype.Component;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;

/**
 * 本地 mock 大模型策略。
 *
 * <p>仅在渠道明确选择 mockLlmStrategy 时通过 query 返回固定文本，
 * 不读取密钥、不发起网络请求，也不作为真实模型失败后的自动降级。</p>
 *
 * @author huangry
 */
@Component
public class MockLlmStrategy implements LLMStrategy {

    /** 声明本地模拟协议，供路由免除远程密钥检查和页面展示。 */
    @Override
    public LLMProtocolType protocolType() { return LLMProtocolType.MOCK; }

    /**
     * 返回固定模拟文本和空引用，用于验证调用链。
     * @param request 本次请求；模拟结果不依赖问题内容
     * @param channel 已选中的模拟渠道；不使用连接参数
     * @return 明确标识未调用真实模型的响应
     */
    @Override
    public LLMResponse query(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        return LLMResponse.withoutCitations("当前配置启用 mock 大模型，未调用真实大模型服务。");
    }
}
