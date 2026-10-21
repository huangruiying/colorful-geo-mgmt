package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

/** geo_diagnosis_plan 映射；仅保存方案公共配置，不代表诊断已执行。 */
@Data
@TableName("geo_diagnosis_plan")
public class GeoDiagnosisPlanEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String name;
    private Long projectId;
    private Integer repeatCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
