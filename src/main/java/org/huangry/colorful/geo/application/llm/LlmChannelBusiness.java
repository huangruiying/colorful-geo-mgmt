package org.huangry.colorful.geo.application.llm;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.llm.model.*;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import java.util.NoSuchElementException;

/** 模型管理应用编排；保存命令与分页输入转换，远程调用由客户端负责。 */
@Service
public class LlmChannelBusiness {
    @Resource
    private GeoLlmChannelService service;

    /** 将页面命令转换为渠道资料，系统字段不能由输入覆盖。 */
    public LlmChannelResponse saveChannel(Long id, SaveLlmChannelCommand command) {
        if (command == null) throw new IllegalArgumentException("请填写模型配置");
        // 转换业务字段，再交给领域规则与持久化。
        GeoLlmChannelEntity channel = new GeoLlmChannelEntity();
        BeanUtils.copyProperties(command, channel);
        return toResponse(service.saveChannel(id, channel));
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
        BeanUtils.copyProperties(entity, response);
        response.setKeyConfigured(entity.getApiKey() != null && !entity.getApiKey().isBlank());
        return response;
    }
}
