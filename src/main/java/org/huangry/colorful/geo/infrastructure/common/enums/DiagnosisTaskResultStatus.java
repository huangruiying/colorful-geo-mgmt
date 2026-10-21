package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 诊断任务中单条诊断结果的执行状态。
 * 用于单次诊断的领取、结果保存及进度统计；数据库及接口沿用枚举名称，单条结果不存在部分失败状态。
 * 通常由 PENDING 进入 RUNNING 后结束；任务中断时，尚未执行的诊断也可直接转为 FAILED。
 */
public enum DiagnosisTaskResultStatus {
    /** 诊断结果记录已初始化，等待后台执行本次诊断。 */
    PENDING,
    /** 本次诊断已开始，尚未保存最终结果。 */
    RUNNING,
    /** 已成功保存本次模型回答。 */
    SUCCEEDED,
    /** 本次诊断失败或因任务中断被终止，不自动重试。 */
    FAILED
}
