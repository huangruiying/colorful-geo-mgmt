package org.huangry.colorful.geo.domain.service.diagnosis.report.summary;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService.ReportAnswer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.analyze.ResultRuleAnalyzer;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskResultAnalysisStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 诊断报告汇总入口：把报告的全部回答整理成统计事实，交给各页签的汇总类计算，拼成冻结到报告上的 summary。
 * <p>各页签分工：① ReportOverviewSummarizer，② ReportVisibilitySummarizer，③ ReportBrandSummarizer，④ ReportCompetitorSummarizer，
 * ⑤ ReportSourceSummarizer，⑥ 规则问题 ReportIssueSummarizer；⑥ 的建议文字由后台执行器调用模型生成。</p>
 * <p>纯计算，不调用模型、不访问数据库；结果冻结到报告 summary，之后不随规则或数据变化重算。</p>
 */
@Component
public class ReportSummarizer {
    /** 一份完整报告 summary 应有的全部部分；advice 由后台执行器在汇总后写入，其余由 buildSummary 产出。新增部分时只改这里。 */
    public static final List<String> SUMMARY_PARTS = List.of("sample", "models", "strength", "reservations", "criteria", "concerns",
            "brand", "competitor", "source", "issues", "advice");

    @Resource
    private ResultRuleAnalyzer resultRuleAnalyzer;
    @Resource
    private DiagnosisTaskSnapshots diagnosisTaskSnapshots;
    @Resource
    private ReportOverviewSummarizer reportOverviewSummarizer;
    @Resource
    private ReportVisibilitySummarizer reportVisibilitySummarizer;
    @Resource
    private ReportBrandSummarizer reportBrandSummarizer;
    @Resource
    private ReportCompetitorSummarizer reportCompetitorSummarizer;
    @Resource
    private ReportSourceSummarizer reportSourceSummarizer;
    @Resource
    private ReportIssueSummarizer reportIssueSummarizer;

    /**
     * 计算并组装冻结的汇总。
     * @param answers 报告的全部回答（含判定失败的）
     * @param project 任务快照中的项目资料
     * @param dimensionMapping 维度归一映射，未覆盖的维度保持原名
     * @param judgeChannel 判定渠道快照
     * @param promptVersion 判定提示词版本
     * @return 汇总 JSON：①② 使用 sample、models、strength、reservations、criteria、concerns；
     *         ③ 品牌认知使用 brand，④ 竞品对比使用 competitor，⑤ 引用与内容使用 source，⑥ 使用 issues（advice 由后台执行器另行写入）
     */
    public JSONObject buildSummary(List<ReportAnswer> answers, JSONObject project, Map<String, String> dimensionMapping,
                                   JSONObject judgeChannel, String promptVersion) {
        // 1. 逐条整理成统计用的事实：模型、问题、分组、是否判定成功、是否提到目标、目标品牌项、来源
        List<String> targetNames = resultRuleAnalyzer.listTargetNames(project);
        List<ReportAnswerFacts> facts = answers.stream().map(answer -> buildAnswerFacts(answer, targetNames)).toList();
        List<ReportAnswerFacts> judged = facts.stream().filter(ReportAnswerFacts::judged).toList();
        // 2. ① 概览：样本说明、各模型核心指标；② 可见度：推荐强度、维度排行、按意图拆分
        JSONObject summary = new JSONObject(true);
        summary.putAll(reportOverviewSummarizer.buildOverview(facts, judged, judgeChannel, promptVersion));
        summary.putAll(reportVisibilitySummarizer.buildVisibility(judged, targetNames, dimensionMapping));
        // 3. ③④⑤ 各页签一个对象：品牌认知、竞品对比、引用与内容
        summary.put("brand", reportBrandSummarizer.buildBrandPerception(judged, dimensionMapping));
        summary.put("competitor", reportCompetitorSummarizer.buildCompetitorComparison(judged, project, dimensionMapping));
        summary.put("source", reportSourceSummarizer.buildSourceAnalysis(facts, judged, dimensionMapping));
        // 4. ⑥ 规则发现的问题：从上面算好的各部分里按阈值挑出问题并附证据，供模型写建议、页面查看证据
        summary.put("issues", reportIssueSummarizer.buildIssues(summary, judged));
        return summary;
    }

    /**
     * 找出报告 summary 缺少的部分；缺少任何一部分，页面就提供补全。
     * @param summary 报告冻结的汇总
     * @return 缺少的部分名称，按 SUMMARY_PARTS 顺序；内容完整时为空列表
     */
    public static List<String> listMissingParts(JSONObject summary) {
        return SUMMARY_PARTS.stream().filter(part -> !summary.containsKey(part)).toList();
    }

    /**
     * 把一条回答整理成各页签统计用的事实；判定失败的回答没有品牌与观点，但有来源。
     * @param answer 一条回答
     * @param targetNames 目标名称与别名
     * @return 统计用事实
     */
    private ReportAnswerFacts buildAnswerFacts(ReportAnswer answer, List<String> targetNames) {
        JSONObject channel = (JSONObject) diagnosisTaskSnapshots.parseSnapshot(answer.result().getChannelSnapshot());
        JSONObject question = (JSONObject) diagnosisTaskSnapshots.parseSnapshot(answer.result().getQuestionSnapshot());
        boolean judged = DiagnosisTaskResultAnalysisStatus.SUCCEEDED.name().equals(answer.analysis().getStatus());
        JSONArray brands = judged && answer.analysis().getBrands() != null ? JSON.parseArray(answer.analysis().getBrands()) : new JSONArray();
        JSONObject target = brands.stream().map(JSONObject.class::cast).filter(brand -> "TARGET".equals(brand.getString("type")))
                .findFirst().orElse(null);
        // 提及以规则匹配名称或别名为准，判定识别出的其他称呼补充
        boolean mentioned = resultRuleAnalyzer.containsAnyName(answer.result().getAnswerText(), targetNames) || target != null;
        // 来源归类在判定前由规则完成，判定失败的回答也有，因此不看判定状态
        JSONArray sources = answer.analysis().getSources() != null ? JSON.parseArray(answer.analysis().getSources()) : new JSONArray();
        return new ReportAnswerFacts(answer, channel.getLong("id"), channel.getString("channelName"), channel.getString("modelName"),
                answer.result().getQuestionSortOrder(), question.getString("text"), question.getString("brandScope"), question.getString("intent"),
                judged, mentioned, target, brands,
                resultRuleAnalyzer.isSearchPerformed(answer.result().getRawResponse(), answer.result().getCitations()), sources);
    }
}
