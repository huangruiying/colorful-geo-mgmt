package org.huangry.colorful.geo.application.llm;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMCallFailureClassifier;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMCallFailureClassifier.LLMCallFailure;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMStrategyRouter;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.LLMStrategy;
import java.util.Map;
import org.huangry.colorful.geo.application.llm.model.*;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.springframework.stereotype.Service;
import java.util.NoSuchElementException;

/** 模型管理应用编排；保存请求与分页输入转换，测试连接编排一次调用并翻译失败原因，远程调用由客户端负责。 */
@Service
public class LlmChannelBusiness {
    @Resource
    private GeoLlmChannelService service;
    @Resource
    private Map<String, LLMStrategy> strategies;
    @Resource
    private LLMStrategyRouter router;

    /** 测试连接使用的实时问题；联网策略必然触发搜索，便于同时确认联网是否生效。 */
    private static final String CONNECTION_TEST_PROMPT = "今天是几月几日？请给出一条今天的国内新闻标题。";
    /** 测试结果展示的回答长度上限。 */
    private static final int ANSWER_PREVIEW_LIMIT = 200;

    /** 将页面请求转换为渠道资料，系统字段不能由输入覆盖。 */
    public LlmChannelResponse saveChannel(Long id, SaveLlmChannelRequest request) {
        // 转换业务字段，再交给领域规则与持久化。
        return toResponse(service.saveChannel(id, buildChannel(request)));
    }

    /**
     * 用页面当前填写的配置真实调用一次模型，验证连通性与联网效果；不保存配置。
     * <p>每次测试都是一次计费调用，联网策略还会产生搜索费用。</p>
     * @param id 编辑中的渠道 ID，新建时为 null；密钥留空时使用该渠道已保存的密钥
     * @param request 页面当前填写的配置
     * @return 成功时的耗时、模型、引用数与回答摘要；失败时的原因分类与厂商报错原文
     * @throws IllegalArgumentException 配置不合法或远程渠道缺少密钥，不发起调用
     */
    public LlmChannelTestResponse testChannel(Long id, SaveLlmChannelRequest request) {
        // 1. 表单转为渠道配置，与保存使用同一转换
        GeoLlmChannelEntity channel = buildChannel(request);
        // 2. 按保存规则校验；编辑时密钥留空则使用已保存密钥，不写库
        service.prepareTestChannel(id, channel);
        // 3. 用固定实时问题调用一次，联网策略必然触发搜索，可同时验证连通与联网
        long started = System.nanoTime();
        try {
            LLMResponse response = router.query(new LLMQueryRequest(CONNECTION_TEST_PROMPT), channel);
            // 4. 成功返回耗时、实际模型、引用数与回答摘要
            return new LlmChannelTestResponse(true, elapsedMillis(started), response.getModelName(), response.getFinishReason(),
                    response.getCitations().size(), preview(response.getContent()), null, null, null, null);
        } catch (RuntimeException exception) {
            // 4. 失败交给分类器翻译为原因；路由器已记录调用日志，这里不重复打印
            LLMCallFailure failure = LLMCallFailureClassifier.classify(exception);
            return new LlmChannelTestResponse(false, elapsedMillis(started), null, null, 0, null,
                    failure.type().name(), failure.type().getDescription(), failure.httpStatus(), failure.providerMessage());
        }
    }

    /**
     * 将页面请求转换为渠道实体，保存与测试连接共用；系统字段不能由输入覆盖。
     * @param request 页面提交的配置
     * @return 未校验的渠道实体
     * @throws IllegalArgumentException 请求为空
     */
    private GeoLlmChannelEntity buildChannel(SaveLlmChannelRequest request) {
        if (request == null) throw new IllegalArgumentException("请填写模型配置");
        GeoLlmChannelEntity channel = new GeoLlmChannelEntity();
        channel.setChannelCode(request.getChannelCode());
        channel.setChannelName(request.getChannelName());
        channel.setProviderCode(request.getProviderCode());
        channel.setStrategyBeanName(request.getStrategyBeanName());
        channel.setBaseUrl(request.getBaseUrl());
        channel.setModelName(request.getModelName());
        channel.setApiKey(request.getApiKey());
        channel.setTemperature(request.getTemperature());
        channel.setMaxTokens(request.getMaxTokens());
        channel.setTimeoutSeconds(request.getTimeoutSeconds());
        channel.setSslVerify(request.getSslVerify());
        channel.setScore(request.getScore());
        channel.setEnabled(request.getEnabled());
        return channel;
    }

    /**
     * @param started 调用开始的 System.nanoTime
     * @return 至今经过的毫秒数
     */
    private static long elapsedMillis(long started) {
        return (System.nanoTime() - started) / 1_000_000;
    }

    /**
     * @param content 完整回答
     * @return 前 200 字摘要，超出时以省略号结尾
     */
    private static String preview(String content) {
        return content.length() <= ANSWER_PREVIEW_LIMIT ? content : content.substring(0, ANSWER_PREVIEW_LIMIT) + "…";
    }

    /** 查询编辑所需配置。 */
    public LlmChannelResponse getChannel(long id) {
        var channel = service.getById(id);
        if (channel == null) throw new NoSuchElementException("模型渠道不存在");
        return toResponse(channel);
    }

    /** 分页查询模型列表，限制单次查询规模。 */
    public LlmChannelPage listChannels(String keyword, int page, int size) {
        // 校验分页和关键词，再使用同一条件查询列表及总数。
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("页码不能小于零，每页条数必须在 1 到 100 之间");
        if (keyword != null && keyword.length() > 200) throw new IllegalArgumentException("查询关键词最多 200 字符");
        String key = keyword == null ? null : keyword.strip();
        long offset = (long) page * size;
        var items = service.listChannels(key, size, offset).stream()
            .map(this::toResponse)
            .toList();
        long total = service.countChannels(key);
        return new LlmChannelPage(items, total, page, size);
    }

    /** 启用或停用已有渠道。 */
    public LlmChannelResponse changeEnabled(long id, boolean enabled) { return toResponse(service.changeEnabled(id, enabled)); }

    /** 对外响应不携带密钥，只公开是否已经配置。 */
    private LlmChannelResponse toResponse(GeoLlmChannelEntity entity) {
        var response = new LlmChannelResponse();
        response.setId(entity.getId());
        response.setChannelCode(entity.getChannelCode());
        response.setChannelName(entity.getChannelName());
        response.setProviderCode(entity.getProviderCode());
        var strategy = strategies.get(entity.getStrategyBeanName());
        response.setStrategyBeanName(entity.getStrategyBeanName());
        response.setProtocolType(strategy == null ? null : strategy.protocolType());
        response.setBaseUrl(entity.getBaseUrl());
        response.setModelName(entity.getModelName());
        response.setTemperature(entity.getTemperature());
        response.setMaxTokens(entity.getMaxTokens());
        response.setTimeoutSeconds(entity.getTimeoutSeconds());
        response.setSslVerify(entity.getSslVerify());
        response.setScore(entity.getScore());
        response.setEnabled(entity.getEnabled());
        response.setCreatedAt(entity.getCreatedAt());
        response.setUpdatedAt(entity.getUpdatedAt());
        response.setKeyConfigured(entity.getApiKey() != null && !entity.getApiKey().isBlank());
        return response;
    }
}
