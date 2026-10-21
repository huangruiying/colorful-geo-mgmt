package org.huangry.colorful.geo.application.diagnosis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisTaskReportResponse;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService.ReportAnswer;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService;
import org.huangry.colorful.geo.domain.service.diagnosis.report.llm.ReportLlmAnalyzer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportSummarizer;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskReportStatus;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskReportDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskReportEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 补全诊断报告：已完成报告的 summary 缺少任何部分时，用已保存的判定结果一次补算全部缺失内容并入 summary。
 * <p>只做补全这一件事，不建报告、不重新判定回答、不改报告状态，与生成报告和后台执行器互不调用；
 * 复用报告服务、汇总入口、模型两步分析这些公共能力。一次补全调用两次模型（维度归一、优化建议），同步返回。
 * 同一份报告同时只允许一次补全，不同报告之间互不影响。</p>
 */
@Service
@Slf4j
public class DiagnosisTaskReportSupplementBusiness {
    /** 本实例正在补全的报告 ID，防止同一份报告被重复补全、重复调用模型；不同报告可以同时补全。 */
    private final Set<Long> supplementingReportIds = ConcurrentHashMap.newKeySet();

    @Resource
    private GeoDiagnosisTaskReportDao geoDiagnosisTaskReportDao;
    @Resource
    private GeoDiagnosisTaskReportService geoDiagnosisTaskReportService;
    @Resource
    private ReportSummarizer reportSummarizer;
    @Resource
    private ReportLlmAnalyzer reportLlmAnalyzer;
    @Resource
    private DiagnosisTaskSnapshots diagnosisTaskSnapshots;
    @Resource
    private GeoLlmChannelService geoLlmChannelService;
    @Resource
    private DiagnosisTaskReportBusiness diagnosisTaskReportBusiness;

    /**
     * 补全一份报告：读已保存的判定结果重算汇总，只把缺少的部分并入原 summary，已有部分保持原结论。
     * @param reportId 报告 ID
     * @return 补全后的报告详情
     * @throws NoSuchElementException 报告不存在
     * @throws IllegalArgumentException 报告正在补全、未完成、内容完整，或冻结的判定渠道已被删除
     */
    public DiagnosisTaskReportResponse supplementReport(long reportId) {
        // 同一份报告正在补全时直接拒绝，结束后释放，不同报告不受影响
        if (!supplementingReportIds.add(reportId)) {
            throw new IllegalArgumentException("该报告正在补全，请稍候");
        }
        try {
            return supplementReportOnce(reportId);
        } finally {
            supplementingReportIds.remove(reportId);
        }
    }

    /**
     * 执行一次补全；调用方已保证同一份报告不会并发进入。
     * @param reportId 报告 ID
     * @return 补全后的报告详情
     */
    private DiagnosisTaskReportResponse supplementReportOnce(long reportId) {
        // 1. 确认可以补全：报告已完成、汇总有缺失部分，判定渠道仍在
        GeoDiagnosisTaskReportEntity report = getCompletedReport(reportId);
        JSONObject summary = (JSONObject) diagnosisTaskSnapshots.parseSnapshot(report.getSummary());
        List<String> missingParts = ReportSummarizer.listMissingParts(summary);
        if (missingParts.isEmpty()) {
            throw new IllegalArgumentException("该报告内容完整，无需补全");
        }
        JSONObject judgeChannel = (JSONObject) diagnosisTaskSnapshots.parseSnapshot(report.getJudgeChannelSnapshot());
        GeoLlmChannelEntity channel = getJudgeChannel(judgeChannel);

        // 2. 用已保存的判定结果重算汇总，维度归一与优化建议沿用报告冻结的判定渠道
        JSONObject project = geoDiagnosisTaskReportService.getTaskSnapshot(report.getTaskId()).getJSONObject("project");
        List<ReportAnswer> answers = geoDiagnosisTaskReportService.loadAnswers(reportId);
        Map<String, String> mapping = reportLlmAnalyzer.normalizeDimensions(answers, channel, reportId);
        JSONObject recalculated = reportSummarizer.buildSummary(answers, project, mapping, judgeChannel, report.getPromptVersion());
        if (missingParts.contains("advice")) {
            recalculated.put("advice", reportLlmAnalyzer.generateAdvice(recalculated.getJSONArray("issues"), project, channel, reportId));
        }

        // 3. 只并入缺少的部分，已有部分保持生成时冻结的结论
        missingParts.forEach(part -> summary.put(part, recalculated.get(part)));

        // 4. 保存补全后的汇总
        geoDiagnosisTaskReportService.updateReportSummarySupplemented(reportId, JSON.toJSONString(summary, SerializerFeature.WriteMapNullValue));
        log.info("诊断报告补全完成 reportId={}, answers={}, missingParts={}", reportId, answers.size(), missingParts);
        return diagnosisTaskReportBusiness.getReport(reportId);
    }

    /**
     * 读取已完成的报告；只有已完成且有汇总的报告才谈得上补全。
     * @param reportId 报告 ID
     * @return 已完成的报告
     * @throws NoSuchElementException 报告不存在
     * @throws IllegalArgumentException 报告未完成
     */
    private GeoDiagnosisTaskReportEntity getCompletedReport(long reportId) {
        GeoDiagnosisTaskReportEntity report = reportId > 0 ? geoDiagnosisTaskReportDao.selectById(reportId) : null;
        if (report == null) {
            throw new NoSuchElementException("诊断报告不存在");
        }
        if (!DiagnosisTaskReportStatus.SUCCEEDED.name().equals(report.getStatus()) || report.getSummary() == null) {
            throw new IllegalArgumentException("只有已完成的报告可以补全");
        }
        return report;
    }

    /**
     * 读取报告冻结的判定渠道；渠道已删除时不换用其他模型，保持同一判定口径。
     * @param judgeChannel 报告冻结的判定渠道快照
     * @return 判定渠道
     * @throws IllegalArgumentException 渠道已被删除
     */
    private GeoLlmChannelEntity getJudgeChannel(JSONObject judgeChannel) {
        GeoLlmChannelEntity channel = geoLlmChannelService.getById(judgeChannel.getLong("id"));
        if (channel == null) {
            throw new IllegalArgumentException("报告使用的判定渠道已被删除，无法补全，可重新生成报告");
        }
        return channel;
    }
}
