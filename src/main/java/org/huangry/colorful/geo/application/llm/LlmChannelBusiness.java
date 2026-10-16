package org.huangry.colorful.geo.application.llm;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.LLMStrategy;
import java.util.Map;
import org.huangry.colorful.geo.application.llm.model.*;
import org.huangry.colorful.geo.infrastructure.common.model.PageResponse;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.springframework.stereotype.Service;
import java.util.NoSuchElementException;

/** 模型管理应用编排；保存请求与分页输入转换，远程调用由客户端负责。 */
@Service
public class LlmChannelBusiness {
    @Resource
    private GeoLlmChannelService service;
    @Resource
    private Map<String, LLMStrategy> strategies;

    /** 将页面请求转换为渠道资料，系统字段不能由输入覆盖。 */
    public LlmChannelResponse saveChannel(Long id, SaveLlmChannelRequest request) {
        if (request == null) throw new IllegalArgumentException("请填写模型配置");
        // 转换业务字段，再交给领域规则与持久化。
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
        return toResponse(service.saveChannel(id, channel));
    }

    /** 查询编辑所需配置。 */
    public LlmChannelResponse getChannel(long id) {
        var channel = service.getById(id);
        if (channel == null) throw new NoSuchElementException("模型渠道不存在");
        return toResponse(channel);
    }

    /**
     * 模型列表分页查询，可按名称或编码筛选。
     *
     * @param keyword 名称或编码关键词，可为空
     * @param page 从零开始的页码
     * @param size 每页条数，上限 100
     * @return 当前页模型及总数
     */
    public PageResponse<LlmChannelResponse> listChannels(String keyword, int page, int size) {
        // 分页参数和关键词越界直接拒绝，避免一次查询过多记录
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("页码不能小于零，每页条数必须在 1 到 100 之间");
        if (keyword != null && keyword.length() > 200) throw new IllegalArgumentException("查询关键词最多 200 字符");

        // 列表和总数使用同一关键词条件
        String key = keyword == null ? null : keyword.strip();
        long offset = (long) page * size;
        var items = service.listChannels(key, size, offset).stream()
            .map(this::toResponse)
            .toList();
        long total = service.countChannels(key);
        return new PageResponse<>(items, total, page, size);
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
