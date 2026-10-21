package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 诊断报告状态，数据库保存枚举名称。
 * <p>流转：PENDING →（分析任务捞取）RUNNING →（汇总写入）SUCCEEDED 或 FAILED；结束后不再改写，重新生成会新建一份报告。</p>
 */
public enum DiagnosisTaskReportStatus {
	/**
	 * 诊断报告初始化，等待分析任务捞取分析
     * 用户点击生成时写入，同时为每条有效回答建立待判定记录（createReport）。
	 */
	PENDING,
	/**
	 * 分析中：分析任务捞取后写入（extrateUnprocessReport），期间逐条判定回答并汇总。
	 * 服务重启或数据库异常中断时保持此状态，分析任务下次轮询续跑，已判定的回答不重复判定。
	 */
	RUNNING,
	/**
	 * 分析完成：汇总指标冻结到 summary（finishReport）；部分回答判定失败不影响成功，只是不计入依赖判定的指标。
	 */
	SUCCEEDED,
	/**
	 * 分析失败，原因写入 errorMessage（failReport），已保存的回答分析保留。
	 * 仅在以下情况失败：判定渠道被删除或没有已启用渠道、全部回答判定失败、汇总计算异常。
	 */
	FAILED
}
