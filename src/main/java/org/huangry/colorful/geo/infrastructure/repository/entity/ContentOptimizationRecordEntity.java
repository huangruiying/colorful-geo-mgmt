package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMCitation;
import org.huangry.colorful.geo.infrastructure.common.enums.ContentOptimizationStrategy;

import java.time.LocalDateTime;
import java.util.List;

/**
 * content_optimization_record 表映射；保存优化输入、输出及优化元数据。
 */
@Data
@TableName(value = "content_optimization_record", autoResultMap = true)
public class ContentOptimizationRecordEntity {

    /** 数据库自增主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 内容标题。 */
    private String title;
    /** 优化前正文。 */
    private String originalContent;
    /** 模型优化稿。 */
    private String optimizedContent;
    /** 优化方向或偏好。 */
    private String optimizationPreference;
    /** 实际使用的 GEO 策略，保存为 JSON。 */
    @TableField(value = "applied_strategies", typeHandler = JacksonTypeHandler.class)
    private List<ContentOptimizationStrategy> appliedStrategies;
    /** 优化提示，保存为 JSON。 */
    @TableField(value = "optimization_warnings", typeHandler = JacksonTypeHandler.class)
    private List<String> warnings;
    /** 模型明确返回的引用，保存为 JSON。 */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<LLMCitation> citations;
    /** 首次入库时间。 */
    private LocalDateTime createdAt;
    /** 最近修改时间。 */
    private LocalDateTime updatedAt;
}
