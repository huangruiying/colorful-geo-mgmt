package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.List;

/**
 * DeepSeek 官方 Responses 联网问答策略。
 * 要求模型调用 DeepSeek 服务端搜索并由协议父类执行；不在应用侧执行搜索或拼接搜索资料。
 * DeepSeek 回答不返回 url_citation，以接口返回的已打开网页作为引用；检索词不作为引用。
 * 渠道选择由路由器负责，HTTP 超时及连接资源由共用客户端管理。
 */
@Component
public class ResponsesApiDeepSeekStrategy extends AbstractResponsesApiStrategy {
    /** DeepSeek 在 open_page 地址末尾追加的工具调用标识，不属于网页地址。 */
    private static final String WEB_SEARCH_CALL_ID_MARKER = "ws_call_id=";

    /**
     * 在协议基础请求上维护当前策略的参数。
     * @param request 当前问题
     * @param channel 渠道配置
     * @return 本次调用的完整请求体
     */
    @Override
    protected Map<String, Object> buildRequestBody(LLMQueryRequest request, GeoLlmChannelEntity channel) {
        Map<String, Object> body = super.buildRequestBody(request, channel);
        // DeepSeek 搜索来源由服务端决定，不传豆包专用的 sources；强制搜索保证采样结果均为联网回答。
        body.put("tools", List.of(Map.of("type", "web_search")));
        body.put("tool_choice", "required");
        // 多轮搜索与打开网页耗时较长，持续读取事件，完成事件仍通过父类解析并保存完整回答。
        body.put("stream", true);
        return body;
    }

    /**
     * 将模型实际打开的网页补充为引用，使 DeepSeek 与豆包使用同一引用入库及展示链路。
     * <p>接口只返回地址，标题、摘要和正文位置保持空值，不抓取网页补全。</p>
     * @param output Responses 的 output 数组，接口未返回时为 null
     * @param citationByUrl 已从文本注解收集的引用；同一地址保留已有资料
     */
    @Override
    protected void collectStrategyCitations(JSONArray output, Map<String, LLMCitation> citationByUrl) {
        for (Object value : output == null ? List.of() : output) {
            // 只取 open_page：search 仅含检索词，不能证明模型读过某个网页
            if (!(value instanceof JSONObject outputItem) || !"web_search_call".equals(outputItem.getString("type"))
                    || !(outputItem.get("action") instanceof JSONObject action) || !"open_page".equals(action.getString("type"))
                    || !(action.get("url") instanceof String rawUrl)) {
                continue;
            }
            String url = stripWebSearchCallId(rawUrl);
            if (!url.isBlank()) {
                citationByUrl.putIfAbsent(url, new LLMCitation("", url, null, null, ""));
            }
        }
    }

    /**
     * 去掉 DeepSeek 追加在地址末尾的工具调用标识，避免同一网页因调用不同被重复计为多条引用。
     * @param rawUrl 接口返回的 open_page 地址
     * @return 原网页地址；未包含标识时原样返回
     */
    private static String stripWebSearchCallId(String rawUrl) {
        int markerIndex = rawUrl.lastIndexOf(WEB_SEARCH_CALL_ID_MARKER);
        if (markerIndex <= 0) {
            return rawUrl.strip();
        }
        // 实测标识以 # 追加在原地址后，连同 # 一并去掉
        int end = rawUrl.charAt(markerIndex - 1) == '#' ? markerIndex - 1 : markerIndex;
        return rawUrl.substring(0, end).strip();
    }
}
