package org.huangry.colorful.geo.application.diagnosis;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisTaskReportResponse;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskReportStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskResultAnalysisStatus;
import org.huangry.colorful.geo.infrastructure.common.model.PageQuery;
import org.huangry.colorful.geo.infrastructure.common.model.PageResponse;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskResultAnalysisDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskReportDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskReportEntity;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 诊断报告应用编排：手动生成、分页列表与详情；判定与汇总由后台执行器完成，本类不调用模型。
 * <p>方案与项目名称取自诊断任务快照；冻结汇总只在详情返回，避免列表传输大字段。</p>
 *
 * <p>报告生成完整流程（一份报告对应一次诊断任务，报告表 geo_diagnosis_task_report，
 * 逐条分析表 geo_diagnosis_task_result_analysis）：</p>
 * <ol>
 *   <li>用户点击生成：本类 {@code create} 调用 {@code GeoDiagnosisTaskReportService.createReport}，
 *       创建 PENDING 报告，并为每条成功的诊断结果建一条 PENDING 分析记录，随即返回，不等待判定。</li>
 *   <li>后台领取：{@code DiagnosisTaskReportWorker} 每秒轮询，取得数据库锁后领取报告（优先续跑中断的 RUNNING），
 *       首次冻结判定渠道（分数最高的已启用渠道），续跑沿用同一渠道。</li>
 *   <li>逐条分析每条诊断结果，失败只记录本条原因并继续：
 *     <ul>
 *       <li>规则归类引用来源（我方发布、自有域名、提到目标、提到竞品、其他）：{@code DiagnosisTaskResultRuleAnalyzer.classifySources}</li>
 *       <li>模型判定品牌、观点、情感等：{@code DiagnosisTaskResultAnalysisPrompt} 组装提示词并校验解析，Worker 调用模型，
 *           结果写入分析记录</li>
 *     </ul>
 *   </li>
 *   <li>汇总：全部分析结束后，若无一条判定成功则报告失败；否则
 *     <ul>
 *       <li>维度归一：把同义维度合并为统一名称，由 {@code DiagnosisTaskReportSummarizer} 组装提示词并解析，Worker 调用模型；
 *           失败时保持原名，不阻断报告</li>
 *       <li>指标计算：{@code DiagnosisTaskReportSummarizer.summarize}，计算时按规则识别是否提及目标、是否实际联网，
 *           得出样本说明、各模型核心指标、推荐强度、维度排行和按意图拆分，供页面 ① 概览、② 可见度与推荐展示</li>
 *     </ul>
 *   </li>
 *   <li>冻结：{@code GeoDiagnosisTaskReportService.finish} 把汇总 JSON 写入报告 summary 并标记 SUCCEEDED，
 *       之后不随规则或数据变化重算；汇总异常时 {@code fail} 标记 FAILED 并记录原因。</li>
 *   <li>查看：本类 {@code list} 返回报告列表与判定进度，{@code detail} 返回冻结的汇总。</li>
 * </ol>
 */
@Service
public class DiagnosisTaskReportBusiness {
    @Resource
    private GeoDiagnosisTaskReportService reportService;
    @Resource
    private GeoDiagnosisTaskReportDao reportDao;
    @Resource
    private GeoDiagnosisTaskResultAnalysisDao analysisDao;
    @Resource
    private GeoDiagnosisTaskDao taskDao;
    @Resource
    private DiagnosisTaskSnapshots snapshots;

    /**
     * 为一次诊断任务生成报告：创建报告与待判定记录后立即返回，后台执行器领取并判定。
     * @param diagnosisTaskId 要生成报告的诊断任务 ID（geo_diagnosis_task.id）
     * @return 新建的报告，状态为 PENDING
     * @throws IllegalArgumentException 未选择任务、任务未结束或全部失败、已有报告在生成、没有有效回答
     * @throws NoSuchElementException 诊断任务不存在
     */
    public DiagnosisTaskReportResponse createReport(long diagnosisTaskId) {
        // 页面未选择任务时传 0，按输入错误提示而不是“任务不存在”
        if (diagnosisTaskId <= 0) {
            throw new IllegalArgumentException("请选择诊断任务");
        }
        GeoDiagnosisTaskReportEntity report = reportService.createReport(diagnosisTaskId);
        return responses(List.of(report), false).get(0);
    }

    /**
     * 诊断报告列表分页查询，可按诊断任务和状态筛选，最新的在前。
     * @param diagnosisTaskId 可选，按诊断任务 ID（geo_diagnosis_task.id）筛选
     * @param status 可选报告状态
     * @param page 从零开始的页码
     * @param size 每页数量，1 到 100
     * @return 当前页报告及总数，不含汇总
     * @throws IllegalArgumentException 分页参数或状态不合法
     */
    public PageResponse<DiagnosisTaskReportResponse> listReports(Long diagnosisTaskId, String status, int page, int size) {
        // 1. 校验分页与状态，不接受未知筛选条件
        var pageQuery = new PageQuery(page, size);
        boolean filterStatus = status != null && !status.isBlank();
        if (filterStatus && Arrays.stream(DiagnosisTaskReportStatus.values()).noneMatch(value -> value.name().equals(status))) {
            throw new IllegalArgumentException("报告状态不合法");
        }
        // 2. 统计总数后按 ID 倒序取当前页
        LambdaQueryWrapper<GeoDiagnosisTaskReportEntity> query = new LambdaQueryWrapper<GeoDiagnosisTaskReportEntity>()
                .eq(diagnosisTaskId != null, GeoDiagnosisTaskReportEntity::getTaskId, diagnosisTaskId)
                .eq(filterStatus, GeoDiagnosisTaskReportEntity::getStatus, status);
        long total = reportDao.selectCount(query);
        query.select(GeoDiagnosisTaskReportEntity::getId, GeoDiagnosisTaskReportEntity::getTaskId, GeoDiagnosisTaskReportEntity::getStatus,
                        GeoDiagnosisTaskReportEntity::getJudgeChannelSnapshot, GeoDiagnosisTaskReportEntity::getPromptVersion,
                        GeoDiagnosisTaskReportEntity::getErrorMessage, GeoDiagnosisTaskReportEntity::getCreatedAt,
                        GeoDiagnosisTaskReportEntity::getStartedAt, GeoDiagnosisTaskReportEntity::getFinishedAt)
                .orderByDesc(GeoDiagnosisTaskReportEntity::getId).last(pageQuery.limitClause());
        return pageQuery.toResponse(responses(reportDao.selectList(query), false), total);
    }

    /**
     * 查看报告详情，完成后包含冻结的汇总。
     * @param reportId 报告 ID
     * @return 报告详情
     * @throws NoSuchElementException 报告不存在
     */
    public DiagnosisTaskReportResponse getReport(long reportId) {
        GeoDiagnosisTaskReportEntity report = reportId > 0 ? reportDao.selectById(reportId) : null;
        if (report == null) {
            throw new NoSuchElementException("诊断报告不存在");
        }
        return responses(List.of(report), true).get(0);
    }

    /**
     * 批量组装响应：一次查询所属任务与判定进度，避免逐份报告往返。
     * @param reports 报告
     * @param detail 是否返回冻结汇总
     * @return 与输入顺序一致的响应
     */
    private List<DiagnosisTaskReportResponse> responses(List<GeoDiagnosisTaskReportEntity> reports, boolean detail) {
        if (reports.isEmpty()) {
            return List.of();
        }
        // 1. 读取所属任务快照中的方案与项目名称
        Map<Long, GeoDiagnosisTaskEntity> tasks = taskDao.selectBatchIds(
                        reports.stream().map(GeoDiagnosisTaskReportEntity::getTaskId).distinct().toList()).stream()
                .collect(Collectors.toMap(GeoDiagnosisTaskEntity::getId, Function.identity()));
        // 2. 按报告汇总判定状态数量
        Map<Long, long[]> progress = new HashMap<>();
        for (Map<String, Object> row : analysisDao.countStatuses(reports.stream().map(GeoDiagnosisTaskReportEntity::getId).toList())) {
            long[] counts = progress.computeIfAbsent(((Number) row.get("report_id")).longValue(), key -> new long[3]);
            int index = switch (DiagnosisTaskResultAnalysisStatus.valueOf((String) row.get("status"))) {
                case PENDING -> 0;
                case SUCCEEDED -> 1;
                case FAILED -> 2;
            };
            counts[index] += ((Number) row.get("analysis_count")).longValue();
        }
        // 3. 组装响应，判定渠道与汇总以 JSON 对象返回
        return reports.stream().map(report -> {
            GeoDiagnosisTaskEntity task = tasks.get(report.getTaskId());
            JSONObject snapshot = task == null ? new JSONObject() : (JSONObject) snapshots.parseSnapshot(task.getPlanSnapshot());
            long[] counts = progress.getOrDefault(report.getId(), new long[3]);
            return new DiagnosisTaskReportResponse(report.getId(), report.getTaskId(), snapshot.getString("planName"),
                    snapshot.getJSONObject("project") == null ? null : snapshot.getJSONObject("project").getString("name"),
                    report.getStatus(),
                    new DiagnosisTaskReportResponse.ProgressResponse(counts[0] + counts[1] + counts[2], counts[0], counts[1], counts[2]),
                    report.getJudgeChannelSnapshot() == null ? null : (JSONObject) snapshots.parseSnapshot(report.getJudgeChannelSnapshot()),
                    report.getPromptVersion(),
                    detail && report.getSummary() != null ? (JSONObject) snapshots.parseSnapshot(report.getSummary()) : null,
                    report.getErrorMessage(), report.getCreatedAt(), report.getStartedAt(), report.getFinishedAt());
        }).toList();
    }
}
