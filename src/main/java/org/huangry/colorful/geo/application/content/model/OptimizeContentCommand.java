package org.huangry.colorful.geo.application.content.model;

import org.huangry.colorful.geo.domain.model.ContentOptimizationReference;
import org.huangry.colorful.geo.infrastructure.common.enums.ContentOptimizationStrategy;

import java.util.List;

/**
 * 创建并保存优化记录的输入；标题属于内容记录，不传给领域优化服务。
 *
 * @param title 内容标题
 * @param content 优化前正文
 * @param optimizationPreference 可选优化偏好
 * @param strategies 可选优化策略；空列表使用领域服务默认策略
 * @param references 可选参考资料
 */
public record OptimizeContentCommand(String title, String content, String optimizationPreference,
                                     List<ContentOptimizationStrategy> strategies,
                                     List<ContentOptimizationReference> references) {
}
