package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import org.springframework.stereotype.Component;

/**
 * 通用 Chat Completions 文本问答策略。
 * 组装基础请求并由协议父类执行；不主动声明联网搜索工具。
 * 渠道选择由路由器负责，HTTP 超时及连接资源由共用客户端管理。
 */
@Component
public class ChatCompletionsApiCommonStrategy extends AbstractChatCompletionsApiStrategy {
    /**
     * 声明配置页面展示的策略名称。
     * @return 通用兼容
     */
    @Override
    public String displayName() {
        return "通用兼容";
    }
}
