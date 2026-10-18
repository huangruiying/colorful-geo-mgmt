package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 诊断结果分析状态，对应 geo_diagnosis_task_result_analysis.status，表示报告中单条诊断结果的判定进度。
 * 报告创建时为每条有效回答建立 PENDING 记录，判定完成后转为 SUCCEEDED 或 FAILED；数据库保存枚举名称。
 */
public enum DiagnosisTaskResultAnalysisStatus {
    /** 已纳入本次报告，等待判定。 */
    PENDING,
    /** 判定结果已保存。 */
    SUCCEEDED,
    /** 判定失败，原因记录在分析记录上，该回答不计入依赖判定的指标。 */
    FAILED
}
