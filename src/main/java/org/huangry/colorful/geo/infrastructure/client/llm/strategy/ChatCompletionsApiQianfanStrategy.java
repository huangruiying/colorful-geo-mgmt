package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/**
 * 百度千帆（文心）智能搜索生成问答策略。
 * 渠道接口地址配置为 {@code https://qianfan.baidubce.com/v2/ai_search}，由协议父类拼接 /chat/completions；
 * 该接口每次先执行百度搜索再生成回答，弥补普通对话接口 ERNIE 无法强制联网的问题。不在应用侧执行搜索。
 * 引用取响应顶层 references 中的网页来源；渠道选择由路由器负责，HTTP 资源由共用客户端管理。
 */
@Component
public class ChatCompletionsApiQianfanStrategy extends AbstractChatCompletionsApiStrategy {
    /**
     * 声明配置页面展示的策略名称。
     * @return 百度千帆
     */
    @Override
    public String displayName() {
        return "百度千帆";
    }

    /** 每次检索的网页数量，与普通对话联网返回的来源数一致。 */
    private static final int WEB_SEARCH_TOP_K = 10;

    /**
     * 声明百度智能云 API Key 管理页，供配置页面引导获取密钥。
     * @return 百度智能云 API Key 管理页地址
     */
    @Override
    public String apiKeyConsoleUrl() {
        return "https://console.bce.baidu.com/iam/#/iam/apikey/list";
    }

    /**
     * 在协议基础请求上维护当前策略的参数。
     * @param request 当前问题
     * @param channel 渠道配置
     * @return 本次调用的完整请求体
     */
    @Override
    protected Map<String, Object> buildRequestBody(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        Map<String, Object> body = super.buildRequestBody(request, channel);
        // 只检索网页来源，与其他厂商的网页引用口径一致；图片、视频来源不参与引用统计。
        body.put("search_source", "baidu_search_v2");
        body.put("resource_type_filter", List.of(Map.of("type", "web", "top_k", WEB_SEARCH_TOP_K)));
        return body;
    }

    /**
     * 将智能搜索返回的网页来源补充为引用，使文心与豆包使用同一引用入库及展示链路。
     * <p>保留接口返回的标题；网页正文摘录较长，只保留在原始响应中，摘要和位置留空。</p>
     * @param response 厂商原始响应对象
     * @param citations 已从回答注解收集的引用；同一地址保留已有资料
     */
    @Override
    protected void collectStrategyCitations(JSONObject response, Map<String, LLMCitation> citations) {
        if (!(response.get("references") instanceof JSONArray references)) {
            return;
        }
        for (Object value : references) {
            // 只取网页类型且带地址的来源，同一网页只记一次
            if (value instanceof JSONObject reference && "web".equals(reference.getString("type"))
                    && reference.get("url") instanceof String url && !url.isBlank()) {
                String title = reference.get("title") instanceof String text ? text : "";
                citations.putIfAbsent(url.strip(), new LLMCitation(title, url.strip(), null, null, ""));
            }
        }
    }
}
