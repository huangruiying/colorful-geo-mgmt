package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 映射 geo_diagnosis_task，保存一次诊断任务（诊断方案的一次执行）的配置快照与状态；通过 DAO 持久化，不负责执行调度。 */
@Data
@TableName("geo_diagnosis_task")
public class GeoDiagnosisTaskEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 同一次创建提交使用同一标识，数据库唯一键防止重复建任务；历史记录可为空。 */
    private String requestId;
    /** 来源方案 ID；历史执行不随方案编辑重新生成。 */
    private Long planId;
    /** 创建任务时关联的项目 ID，历史项目资料以快照为准。 */
    private Long projectId;
    /** JSON 字符串：项目资料、方案、问题集合与渠道非敏感配置，不保存凭据。 */
    private String planSnapshot;
    /** 整轮任务状态，保存 {@link org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskStatus} 的枚举名称。 */
    private String status;
    /** 实际开始执行的时间，待执行时为空。 */
    private LocalDateTime startedAt;
    /** 本轮执行结束的时间，尚未结束时为空。 */
    private LocalDateTime finishedAt;
    /** 由数据库生成的创建时间，插入后重新查询回读。 */
    private LocalDateTime createdAt;
    /** 由数据库维护的最后更新时间，更新后重新查询回读。 */
    private LocalDateTime updatedAt;
}
