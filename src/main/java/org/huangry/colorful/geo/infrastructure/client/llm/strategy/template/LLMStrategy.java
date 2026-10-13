package org.huangry.colorful.geo.infrastructure.client.llm.strategy.template;

import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;

/**
 * 大模型策略契约，query 执行已选中渠道的一次独立问答。
 *
 * <p>实现负责协议和厂商参数适配，返回统一正文与引用；
 * 渠道选择由路由器完成，不在策略内切换模型或编排诊断业务。</p>
 *
 * @author huangry
 */
public interface LLMStrategy {


    /**
     * 声明交互协议，供页面展示和路由识别本地模拟。
     * @return 实际请求和响应使用的协议，不代表联网能力
     */
    LLMProtocolType protocolType();

    /**
     * 调用已匹配的模型通道。
     *
     * @param request 通用查询请求
     * @param channel 路由已选中的渠道，包含连接、模型和密钥配置
     * @return 标准查询结果
     */
    LLMResponse query(LLMQueryRequest request, GeoLlmChannelEntity channel);
}
