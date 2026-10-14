package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 诊断任务的整轮执行状态。
 * 用于任务领取、恢复和结果汇总；数据库及接口沿用枚举名称，不与单条采样状态混用。
 * 任务由 PENDING 进入 RUNNING，再根据采样结果进入成功、部分失败或失败终态。
 */
public enum DiagnosisTaskStatus {
    /** 任务已创建，等待后台执行。 */
    PENDING,
    /** 后台已领取任务，正在逐条执行采样。 */
    RUNNING,
    /** 本轮全部采样成功。 */
    SUCCEEDED,
    /** 本轮采样已全部结束，其中部分成功、部分失败。 */
    PARTIAL_FAILED,
    /** 本轮没有成功采样。 */
    FAILED
}
