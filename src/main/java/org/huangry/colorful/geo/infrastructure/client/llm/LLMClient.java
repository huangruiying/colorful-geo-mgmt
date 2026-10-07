package org.huangry.colorful.geo.infrastructure.client.llm;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
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
     * 发起单轮大模型查询，默认不启用网页搜索。
     *
     * <p>仍通过配置的通道调用大模型；远程通道会发送网络请求，本地模拟通道除外。</p>
     *
     * @param userMessage 用户问题
     * @return 标准模型响应
     */
    public LLMResponse query(String userMessage) {
        return query(LLMQueryRequest.userMessage(userMessage));
    }

    /**
     * 发起通用大模型查询。
     *
     * <p>网页搜索用于检索外部资料辅助模型回答，引用链接用于说明信息来源；
     * 启用搜索不保证每次执行检索或返回引用，也不代表来源已经核验。
     * 当前通道不支持搜索时，会退化为普通模型查询并返回空引用列表。</p>
     *
     * @param request 查询参数
     * @return 标准模型响应
     */
    private LLMResponse query(LLMQueryRequest request) {
        try {
            // 1. 仅记录可定位的通道和能力标识，避免泄漏问题正文与 API Key。
            LOGGER.info("调用大模型，provider={}, webSearchEnabled={}",
                    strategyRouter.getEnabledProviderName(), request.webSearchEnabled());

            // 2. 由基础设施路由器完成 provider 选择、密钥解析和协议适配。
            return strategyRouter.query(request);
        } catch (LLMClientException exception) {
            LOGGER.warn("大模型调用被拒绝，provider={}, reason={}",
                    strategyRouter.getEnabledProviderName(), exception.getMessage());
            throw exception;
        } catch (RuntimeException exception) {
            LOGGER.error("大模型远程调用失败，provider={}", strategyRouter.getEnabledProviderName(), exception);
            throw new LLMClientException("大模型远程调用失败", exception);
        }
    }
}
