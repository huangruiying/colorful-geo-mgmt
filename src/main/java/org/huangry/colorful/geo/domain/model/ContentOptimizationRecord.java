package org.huangry.colorful.geo.domain.model;

import lombok.Builder;
import lombok.Value;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation;
import org.huangry.colorful.geo.infrastructure.common.enums.ContentOptimizationStrategy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一篇内容的优化输入和输出；用户确认后的发布稿由逐平台发布记录保存。
 */
@Value
@Builder(toBuilder = true)
public class ContentOptimizationRecord {

    /** 数据库记录标识。 */
    Long id;
    /** 发布标题。 */
    String title;
    /** 不覆盖的优化前正文。 */
    String originalContent;
    /** 大模型返回的优化正文。 */
    String optimizedContent;
    /** 调用方描述的优化方向或表达偏好。 */
    String optimizationPreference;
    /** 实际下发给模型的策略。 */
    List<ContentOptimizationStrategy> appliedStrategies;
    /** 优化服务给出的事实核验提示。 */
    List<String> warnings;
    /** 模型通道明确返回的结构化引用。 */
    List<LLMCitation> citations;
    /** 首次入库时间。 */
    LocalDateTime createdAt;
    /** 最近修改时间。 */
    LocalDateTime updatedAt;
}
