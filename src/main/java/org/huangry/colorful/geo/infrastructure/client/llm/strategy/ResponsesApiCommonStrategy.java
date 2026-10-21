package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.springframework.stereotype.Component;

/**
 * 通用 Responses 文本问答策略。
 * 组装基础请求并由协议父类执行；不主动声明搜索或其他扩展工具。
 * 渠道选择由路由器负责，HTTP 超时及连接资源由共用客户端管理。
 */
@Component
public class ResponsesApiCommonStrategy extends AbstractResponsesApiStrategy {
}
