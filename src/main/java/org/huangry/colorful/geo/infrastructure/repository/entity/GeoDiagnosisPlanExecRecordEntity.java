package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 映射 geo_diagnosis_plan_exec_record，保存一次执行的配置快照与状态；通过 DAO 持久化，不负责执行调度。 */
@Data
@TableName("geo_diagnosis_plan_exec_record")
public class GeoDiagnosisPlanExecRecordEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long planId;
    private Long projectId;
    private String planSnapshot;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

