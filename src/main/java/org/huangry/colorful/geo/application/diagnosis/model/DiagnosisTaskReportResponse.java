package org.huangry.colorful.geo.application.diagnosis.model;

import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 诊断报告创建、列表与详情接口的响应；名称取自诊断任务快照，判定渠道只返回非敏感快照。
 * 冻结的汇总只在详情返回；顶层空字段不输出到 JSON。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagnosisTaskReportResponse(
        /** 诊断报告 ID（geo_diagnosis_task_report.id）。 */
        Long id,
        /** 所属诊断任务 ID（geo_diagnosis_task.id）。 */
        Long diagnosisTaskId,
        /** 诊断任务创建时冻结的方案名称。 */
        String planName,
        /** 诊断任务创建时冻结的项目名称。 */
        String projectName,
        /** 报告状态：PENDING 待生成、RUNNING 生成中、SUCCEEDED 已完成、FAILED 失败。 */
        String status,
        /** 逐条判定进度，由分析记录实时统计。 */
        ProgressResponse progress,
        /** 判定渠道的非敏感快照，开始判定后才有值。 */
        JSONObject judgeChannel,
        /** 判定提示词版本。 */
        String promptVersion,
        /** 冻结的汇总指标，只在详情且报告完成后返回。 */
        JSONObject summary,
        /** 生成失败原因。 */
        String errorMessage,
        /** 报告创建时间。 */
        LocalDateTime createdAt,
        /** 开始判定时间。 */
        LocalDateTime startedAt,
        /** 生成结束时间。 */
        LocalDateTime finishedAt
) {
    /**
     * 本报告有效回答的判定进度。
     * @param total 纳入分析的有效回答数
     * @param pending 尚未判定的数量
     * @param succeeded 判定成功的数量
     * @param failed 判定失败的数量
     */
    public record ProgressResponse(long total, long pending, long succeeded, long failed) {
    }
}
