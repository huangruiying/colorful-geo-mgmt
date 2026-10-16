package org.huangry.colorful.geo.infrastructure.client.llm.strategy;

import com.alibaba.fastjson.JSONArray;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

/**
 * 智谱（GLM）Responses 联网问答策略。
 * 挂载智谱服务端搜索并由协议父类执行；不在应用侧执行搜索或拼接搜索资料。
 * 智谱回答不返回 url_citation，以接口返回的搜索来源网址作为引用；检索词不作为引用。
 * 渠道选择由路由器负责，HTTP 超时及连接资源由共用客户端管理。
 */
@Component
public class ResponsesApiZhipuStrategy extends AbstractResponsesApiStrategy {
    /**
     * 声明智谱开放平台的 API Key 管理页，供配置页面引导获取密钥。
     * @return 智谱开放平台 API Key 管理页地址
     */
    @Override
    public String apiKeyConsoleUrl() {
        return "https://bigmodel.cn/usercenter/proj-mgmt/apikeys";
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
        // 智谱实测忽略 tool_choice（响应回显为 null），挂载 web_search 后由模型自行决定搜索，不发送无效参数。
        body.put("tools", List.of(Map.of("type", "web_search")));
        // 多轮搜索耗时较长（实测约 46 秒），持续读取事件，完成事件仍通过父类解析并保存完整回答。
        body.put("stream", true);
        return body;
    }

    /**
     * 将搜索来源网址补充为引用，使智谱与豆包使用同一引用入库及展示链路。
     * <p>接口每个来源只返回网址，标题、摘要和正文位置保持空值，不抓取网页补全。</p>
     * @param output Responses 的 output 数组，接口未返回时为 null
     * @param citationByUrl 已从文本注解收集的引用；同一地址保留已有资料
     */
    @Override
    protected void collectStrategyCitations(JSONArray output, Map<String, LLMCitation> citationByUrl) {
        LlmCitationParser.collectSearchSources(output, citationByUrl);
    }
}
