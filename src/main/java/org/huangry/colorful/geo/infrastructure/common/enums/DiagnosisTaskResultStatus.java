package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 诊断任务中单条采样结果的执行状态。
 * 用于采样领取、结果保存及进度统计；数据库及接口沿用枚举名称，单条采样不存在部分失败状态。
 * 通常由 PENDING 进入 RUNNING 后结束；任务中断时，尚未领取的采样也可直接转为 FAILED。
 */
public enum DiagnosisTaskResultStatus {
    /** 采样已初始化，等待领取执行。 */
    PENDING,
    /** 采样已领取，尚未保存最终结果。 */
    RUNNING,
    /** 已成功保存本次模型回答。 */
    SUCCEEDED,
    /** 本次采样失败或因任务中断被终止，不自动重试。 */
    FAILED
}
