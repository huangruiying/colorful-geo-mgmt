package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** geo_diagnosis_plan_question 映射；问题属于单个方案，顺序由 sortOrder 固定。 */
@Data
@TableName("geo_diagnosis_plan_question")
public class GeoDiagnosisPlanQuestionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long planId;
    private String questionText;
    private String source;
    private String intent;
    private String brandScope;
    private String language;
    private Integer sortOrder;
}
