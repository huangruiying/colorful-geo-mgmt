package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 映射 geo_diagnosis_plan_exec_record_result，保存一次独立采样的问题快照及模型响应；通过 DAO 持久化，不负责执行调度。 */
@Data
@TableName("geo_diagnosis_plan_exec_record_result")
public class GeoDiagnosisPlanExecRecordResultEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long execRecordId;
    private Long questionId;
    private Integer questionSortOrder;
    private String questionSnapshot;
    private Long channelId;
    private String channelSnapshot;
    private Integer repeatIndex;
    private String status;
    private String answerText;
    private String citations;
    private String modelName;
    private String usageInfo;
    private Long durationMs;
    private String finishReason;
    private String rawResponse;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}

