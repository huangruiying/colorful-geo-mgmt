package org.huangry.colorful.geo.domain.model;

import org.huangry.colorful.geo.infrastructure.common.enums.ContentOptimizationStrategy;

import java.util.List;
import java.util.Objects;

/**
 * 内容优化领域服务的输入参数。
 *
 * <p>策略为空时由服务采用安全默认策略；参考资料仅为事实增强策略提供允许使用的证据范围。</p>
 *
 * @param content 待优化正文，优化服务要求非空且不能仅包含空白
 * @param optimizationPreference 可选的优化方向或偏好描述，例如面向新手、突出产品优势或使用专业语气
 * @param strategies 优化策略；null 或空列表时使用易于理解、流畅度优化，列表内不允许 null
 * @param references 允许补充事实的参考资料；null 转为空列表，统计、来源或引文策略要求非空，列表内不允许 null
 * @author huangry
 */
public record ContentOptimizationRequest(
        String content,
        String optimizationPreference,
        List<ContentOptimizationStrategy> strategies,
        List<ContentOptimizationReference> references) {

    /** 校验列表元素并保存不可变副本；正文和策略业务约束由优化服务校验。 */
    public ContentOptimizationRequest {
        if (strategies != null && strategies.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("内容优化策略不能为空");
        }
        if (references != null && references.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("内容优化参考资料不能为空");
        }
        strategies = strategies == null ? List.of() : List.copyOf(strategies);
        references = references == null ? List.of() : List.copyOf(references);
    }
}
