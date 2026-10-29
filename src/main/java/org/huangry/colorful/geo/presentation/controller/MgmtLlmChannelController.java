package org.huangry.colorful.geo.presentation.controller;

import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.client.llm.strategy.template.LLMStrategy;
import java.util.Map;
import org.huangry.colorful.geo.application.llm.LlmChannelBusiness;
import org.huangry.colorful.geo.application.llm.model.*;
import org.huangry.colorful.geo.infrastructure.common.model.PageResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import org.huangry.colorful.geo.infrastructure.common.enums.LLMProtocolType;

/** 基础配置模型接口；创建、编辑、列表与启停，不触发远程调用。 */
@RestController
@RequestMapping("/llm-channels")
public class MgmtLlmChannelController {
    @Resource
    private LlmChannelBusiness business;

    @Resource
    private Map<String, LLMStrategy> strategies;

    /** 页面只展示 Spring 实际注册的策略，中文名称、协议和密钥获取页由策略声明。 */
    @GetMapping("/strategies")
    public List<StrategyOption> strategies() {
        return strategies.entrySet().stream().sorted(Map.Entry.comparingByKey())
            .map(entry -> new StrategyOption(entry.getKey(), entry.getValue().displayName(),
                    entry.getValue().protocolType(), entry.getValue().protocolType().getDescription(),
                    entry.getValue().apiKeyConsoleUrl())).toList();
    }

    /** 实际 Bean（保存值）、策略中文名称、协议及其展示名称、厂商 API Key 控制台地址（通用策略为 null），不携带连接或凭据。 */
    public record StrategyOption(String beanName, String displayName, LLMProtocolType protocolType,
                                 String protocolName, String apiKeyConsoleUrl) {}

    /** 创建共享渠道，返回数据库生成标识。 */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LlmChannelResponse create(@RequestBody SaveLlmChannelRequest request) { return business.saveChannel(null, request); }

    /** 保存已有配置，编码不可修改。 */
    @PutMapping("/{id}")
    public LlmChannelResponse update(@PathVariable("id") long id, @RequestBody SaveLlmChannelRequest request) {
        return business.saveChannel(id, request);
    }

    /** 查询指定渠道，供配置页编辑。 */
    @GetMapping("/{id}")
    public LlmChannelResponse get(@PathVariable("id") long id) { return business.getChannel(id); }

    /**
     * 模型列表分页查询，可按名称或编码筛选。
     *
     * @param keyword 名称或编码关键词，可为空
     * @param page 从零开始的页码
     * @param size 每页条数
     * @return 当前页模型及总数
     */
    @GetMapping
    public PageResponse<LlmChannelResponse> list(@RequestParam(value="keyword", required=false) String keyword,
        @RequestParam(value="page", defaultValue="0") int page, @RequestParam(value="size", defaultValue="20") int size) {
        return business.listChannels(keyword, page, size);
    }

    /** 单独调整启用状态，避免完整配置覆盖。 */
    @PatchMapping("/{id}/enabled")
    public LlmChannelResponse enabled(@PathVariable("id") long id, @RequestBody EnabledCommand request) {
        if (request.enabled() == null) throw new IllegalArgumentException("请指定启用状态");
        return business.changeEnabled(id, request.enabled());
    }

    /** 状态更新命令，空值不能被当成停用。 */
    public record EnabledCommand(Boolean enabled) {}

    /** 编码冲突不暴露数据库异常内容。 */
    @ExceptionHandler(DuplicateKeyException.class)
    public ProblemDetail duplicate() { return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "渠道编码已存在"); }
}
