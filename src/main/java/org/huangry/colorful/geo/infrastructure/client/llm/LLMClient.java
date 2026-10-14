package org.huangry.colorful.geo.infrastructure.client.llm;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.infrastructure.client.llm.strategy.LLMStrategyRouter;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 应用层统一使用的大模型查询客户端。
 *
 * <p>负责提供稳定调用入口和调用边界日志，不包含内容优化、报告生成等业务规则。</p>
 *
 * @author huangry
 */
@Service
public class LLMClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(LLMClient.class);

    @Resource
    private LLMStrategyRouter strategyRouter;

    /**
     * 发起单轮大模型查询，请求方式由渠道保存的策略 Bean 决定。
     *
     * <p>仍通过配置的通道调用大模型；远程通道会发送网络请求，本地模拟通道除外。</p>
     *
     * @param userMessage 用户问题
     * @return 标准模型响应
     */
    public LLMResponse query(String userMessage) {
        return query(new LLMQueryRequest(userMessage));
    }

    /**
     * 发起通用大模型查询。
     *
     * <p>网页搜索用于检索外部资料辅助模型回答，引用链接用于说明信息来源；
     * 搜索参数由具体策略处理，不保证模型返回引用，也不代表来源已经核验。
     * 指定渠道不可用时明确失败，不自动切换被测模型。</p>
     *
     * @param request 查询参数
     * @return 标准模型响应
     */
    public LLMResponse query(LLMQueryRequest request) {
        try {
            // 路由器记录实际选中的渠道，此处只负责统一异常边界。
            return strategyRouter.query(request);
        } catch (LLMClientException exception) {
            LOGGER.warn("大模型调用被拒绝，channel={}, reason={}",
                    request.channelCode(), exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error("大模型远程调用失败，channel={}", request.channelCode());
            throw new LLMClientException("大模型远程调用失败", exception);
        }
    }

    /**
     * 执行任务已经冻结并校验的指定渠道，不触发默认路由或降级。
     * @param request 本次独立问题
     * @param channel 已选定渠道及当前凭据
     * @return 接口实际响应
     */
    public LLMResponse query(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        try {
            return strategyRouter.query(request, channel);
        } catch (LLMClientException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LLMClientException("大模型远程调用失败", exception);
        }
    }
}
