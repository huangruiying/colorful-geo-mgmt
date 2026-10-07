package org.huangry.colorful.geo.domain.model;

import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation;
import org.huangry.colorful.geo.infrastructure.common.enums.ContentOptimizationStrategy;

import java.util.List;

/**
 * 内容优化领域服务的执行结果。
 *
 * <p>承载模型返回的优化稿、下发给模型的策略和发布提示，不代表优化效果或事实已经核验。</p>
 *
 * @param optimizedContent 模型返回的优化正文；构造时 null 转为空字符串，发布前仍需审核
 * @param appliedStrategies 本次下发给模型的去重策略，含默认策略；不代表已验证模型逐项执行
 * @param warnings 发布前需要关注的事实核验与审核提示，不是调用失败信息
 * @param citations 通道返回的结构化网页引用；不等同于输入参考资料，未返回时为空列表
 * @author huangry
 */
public record ContentOptimizationResult(
        String optimizedContent,
        List<ContentOptimizationStrategy> appliedStrategies,
        List<String> warnings,
        List<LLMCitation> citations) {

    /** 将空正文和空列表归一化，并保存列表的不可变副本；列表元素不允许 null。 */
    public ContentOptimizationResult {
        optimizedContent = optimizedContent == null ? "" : optimizedContent;
        appliedStrategies = appliedStrategies == null ? List.of() : List.copyOf(appliedStrategies);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        citations = citations == null ? List.of() : List.copyOf(citations);
    }
}
