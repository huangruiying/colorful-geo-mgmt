package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

/** geo_diagnosis_plan_channel 映射；引用共享模型配置，不复制凭据。 */
@Data
@TableName("geo_diagnosis_plan_channel")
public class GeoDiagnosisPlanChannelEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long planId;
    private Long channelId;
}
