package org.huangry.colorful.geo.domain.service.diagnosis.report.llm;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService.ReportAnswer;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 诊断报告汇总阶段需要模型参与的两步：维度归一、优化建议；生成报告的后台执行器与补全旧报告共用。
 * <p>两步都不是核心步骤：调用失败或输出无效时不抛出，维度保持原名、建议记为失败，报告其余内容照常保存。
 * 每步整份报告只调用一次，使用报告冻结的判定渠道。</p>
 */
@Component
@Slf4j
public class ReportLlmAnalyzer {
    @Resource
    private LLMClient llmClient;
    @Resource
    private DimensionPrompt dimensionPrompt;
    @Resource
    private AdvicePrompt advicePrompt;

    /**
     * 收集报告各回答观点里的维度名，调用一次模型把同义维度合并为统一名称；失败时维度保持原名。
     * @param answers 报告的全部回答
     * @param channel 判定渠道
     * @param reportId 报告 ID，仅用于日志
     * @return 原名称到归一名称的映射；没有维度时为空映射
     */
    public Map<String, String> normalizeDimensions(List<ReportAnswer> answers, GeoLlmChannelEntity channel, long reportId) {
        Set<String> dimensions = dimensionPrompt.collectDimensions(answers);
        if (dimensions.isEmpty()) {
            return Map.of();
        }
        try {
            String output = llmClient.query(new LLMQueryRequest(dimensionPrompt.buildPrompt(dimensions)), channel).getContent();
            return dimensionPrompt.parseDimensionMapping(output, dimensions);
        } catch (LLMClientException exception) {
            log.warn("诊断报告维度归一失败，保持原名 reportId={}, dimensions={}", reportId, dimensions.size());
            return dimensions.stream().collect(Collectors.toMap(Function.identity(), Function.identity()));
        }
    }

    /**
     * 调用一次模型，基于规则发现的问题写出主要发现与优化建议；没有问题时不调用。
     * @param issues 规则发现的问题
     * @param project 项目资料
     * @param channel 判定渠道
     * @param reportId 报告 ID，仅用于日志
     * @return advice 对象：status 为 SUCCEEDED（含 findings、actions）、NO_ISSUES（没有需要建议的问题）或 FAILED（含 errorMessage）
     */
    public JSONObject generateAdvice(JSONArray issues, JSONObject project, GeoLlmChannelEntity channel, long reportId) {
        if (issues.isEmpty()) {
            return new JSONObject(true).fluentPut("status", "NO_ISSUES");
        }
        try {
            String output = llmClient.query(new LLMQueryRequest(advicePrompt.buildPrompt(project, issues)), channel).getContent();
            return advicePrompt.parseAdvice(output, issues).fluentPut("status", "SUCCEEDED");
        } catch (LLMClientException | IllegalArgumentException exception) {
            // 建议只是对规则问题的归纳，失败时页面仍能展示规则发现的问题与证据
            log.warn("诊断报告优化建议生成失败 reportId={}, issues={}, errorType={}", reportId, issues.size(), exception.getClass().getSimpleName());
            return new JSONObject(true).fluentPut("status", "FAILED").fluentPut("errorMessage", "优化建议生成失败，可先查看规则发现的问题");
        }
    }
}
