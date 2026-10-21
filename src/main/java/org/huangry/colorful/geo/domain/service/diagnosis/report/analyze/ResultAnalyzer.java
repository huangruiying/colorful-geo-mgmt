package org.huangry.colorful.geo.domain.service.diagnosis.report.analyze;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 诊断报告第 ② 步：分析一条诊断结果，结果写回这条分析记录（geo_diagnosis_task_result_analysis）。
 * <p>规则归类来源 → 调用判定模型 → 校验并保存；失败只记录本条原因，不重试、不切换渠道。由后台执行器逐条调用。</p>
 */
@Component
public class ResultAnalyzer {
    @Resource
    private GeoDiagnosisTaskReportService geoDiagnosisTaskReportService;
    @Resource
    private ResultRuleAnalyzer resultRuleAnalyzer;
    @Resource
    private ResultJudgePrompt resultJudgePrompt;
    @Resource
    private DiagnosisTaskSnapshots diagnosisTaskSnapshots;
    @Resource
    private LLMClient llmClient;

    /**
     * 分析一条诊断结果并保存：成功时写入品牌、观点、情感与来源，失败时写入原因与来源。
     *
     * @param analysis      待分析记录
     * @param project       项目资料
     * @param publishedUrls 我方已发布内容链接
     * @param channel       判定渠道
     */
    public void analyzeResult(GeoDiagnosisTaskResultAnalysisEntity analysis, JSONObject project, Set<String> publishedUrls,
                              GeoLlmChannelEntity channel) {
        // 1. 规则归类来源，判定失败时也保留，供来源分析使用
        GeoDiagnosisTaskResultEntity result = geoDiagnosisTaskReportService.getTaskResult(analysis.getTaskResultId());
        JSONObject question = (JSONObject) diagnosisTaskSnapshots.parseSnapshot(result.getQuestionSnapshot());
        String sources = JSON.toJSONString(resultRuleAnalyzer.classifySources(result.getCitations(), project, publishedUrls), SerializerFeature.WriteMapNullValue);
        try {
            // 2. 一次判定拿全部字段，原文片段校验在解析时完成
            String prompt = resultJudgePrompt.buildPrompt(project, question, result.getAnswerText());
            String output = llmClient.query(new LLMQueryRequest(prompt), channel).getContent();
            ResultJudgePrompt.AnalysisResult judged = resultJudgePrompt.parseAnalysisResult(output, result.getAnswerText(), question.getString("brandScope"), project);
            // 3. 保存判定成功的结果
            analysis.setBrandSentiment(judged.brandSentiment());
            analysis.setDescriptionAccurate(judged.descriptionAccurate());
            analysis.setBrands(JSON.toJSONString(judged.brands(), SerializerFeature.WriteMapNullValue));
            analysis.setPoints(JSON.toJSONString(judged.points(), SerializerFeature.WriteMapNullValue));
            analysis.setSources(sources);
            geoDiagnosisTaskReportService.updateAnalysisSucceeded(analysis);
        } catch (LLMClientException exception) {
            // 模型调用失败只记录本条，不重试、不切换渠道
            geoDiagnosisTaskReportService.updateAnalysisFailed(analysis.getId(), failureReason(exception), sources);
        } catch (IllegalArgumentException exception) {
            // 判定输出不是有效 JSON，本条不计入依赖判定的指标
            geoDiagnosisTaskReportService.updateAnalysisFailed(analysis.getId(), "判定结果格式无效", sources);
        }
    }

    /**
     * 以异常类型给出安全原因，不持久化远端响应或认证信息。
     *
     * @param exception 模型调用异常
     * @return 可展示的原因
     */
    private String failureReason(LLMClientException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.net.SocketTimeoutException) {
                return "判定模型调用超时";
            }
            if (cause instanceof org.springframework.web.client.HttpStatusCodeException http) {
                return http.getStatusCode().value() == 429 ? "判定模型限制调用频率" : "判定模型拒绝请求，请检查渠道权限与余额";
            }
        }
        return "判定模型不可用或未返回有效回答";
    }
}
