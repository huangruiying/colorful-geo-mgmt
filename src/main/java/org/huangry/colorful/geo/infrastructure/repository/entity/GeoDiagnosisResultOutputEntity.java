package org.huangry.colorful.geo.infrastructure.repository.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

/** 映射 geo_diagnosis_result_output，保存一次手动生成的诊断报告状态与冻结结果；通过 DAO 持久化，不负责判定与汇总。 */
@Data
@TableName("geo_diagnosis_result_output")
public class GeoDiagnosisResultOutputEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 所属诊断任务 ID；同一任务可多次生成报告，旧报告保留。 */
    private Long execRecordId;
    /** 报告生成状态，保存 {@link org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisReportStatus} 的枚举名称。 */
    private String status;
    /** JSON 字符串：判定所用渠道（默认渠道，即已启用渠道中分数最高者）的非敏感配置，不保存凭据；开始判定时写入。 */
    private String judgeChannelSnapshot;
    /** 判定提示词版本，用于说明报告结论的判定口径。 */
    private String promptVersion;
    /** JSON 字符串：冻结的汇总指标、主要发现与优化建议；生成成功后写入，之后不随规则或数据变化重算。 */
    private String summary;
    /** 脱敏后的生成失败原因，不保存远程异常原文。 */
    private String errorMessage;
    /** 开始判定的时间，待生成时为空。 */
    private LocalDateTime startedAt;
    /** 生成结束的时间，尚未结束时为空。 */
    private LocalDateTime finishedAt;
    /** 由数据库生成的创建时间，插入后重新查询回读。 */
    private LocalDateTime createdAt;
    /** 由数据库维护的最后更新时间。 */
    private LocalDateTime updatedAt;
}
