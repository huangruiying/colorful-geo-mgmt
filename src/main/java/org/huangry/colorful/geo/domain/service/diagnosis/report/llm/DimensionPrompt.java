package org.huangry.colorful.geo.domain.service.diagnosis.report.llm;

import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService.ReportAnswer;
import org.huangry.colorful.geo.infrastructure.client.llm.output.LLMJsonOutputParser;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.listPoints;

/**
 * 诊断报告维度归一提示词：收集各回答观点里的维度名，组装“合并同义维度”的提示词，并解析模型返回的映射；本类不调用模型。
 * <p>例如“价格偏高”“收费贵”合并为“价格”，排行时才不会被不同写法拆散。整份报告调用一次，调用由 ReportLlmAnalyzer 完成。</p>
 */
@Component
public class DimensionPrompt {
    /** 需要按维度合并同义写法的观点类型：选择标准、顾虑、保留意见、推荐理由、正负面点、卖点、对比维度。 */
    private static final Set<String> DIMENSION_POINT_TYPES = Set.of("SELECTION_CRITERION", "USER_CONCERN", "RESERVATION",
            "RECOMMEND_REASON", "POSITIVE", "NEGATIVE", "SELLING_POINT_COVERED", "DIMENSION_RESULT");

    /**
     * 收集需要归一的维度名；报告页按维度排行的观点类型都在 DIMENSION_POINT_TYPES 中。
     * @param answers 报告的全部回答
     * @return 去重后的维度名，保持首次出现顺序
     */
    public Set<String> collectDimensions(List<ReportAnswer> answers) {
        Set<String> dimensions = new LinkedHashSet<>();
        for (ReportAnswer answer : answers) {
            for (JSONObject point : listPoints(answer)) {
                if (DIMENSION_POINT_TYPES.contains(point.getString("type")) && point.getString("dimension") != null) {
                    dimensions.add(point.getString("dimension"));
                }
            }
        }
        return dimensions;
    }

    /**
     * 组装维度归一提示词：要求模型把含义相同的维度合并为统一名称，只输出“原名称 → 归一名称”的 JSON。
     * @param dimensions 待归一的维度名
     * @return 提示词
     */
    public String buildPrompt(Collection<String> dimensions) {
        return """
                下面是从多条 AI 回答中提取的维度名称（用户选择标准、顾虑、产品优缺点、推荐理由、比较维度等），存在同义或近义写法。
                请把含义相同的维度合并为一个简短、通用的中文名称（2 到 6 个字），含义不同的保持分开。
                严格输出一个 JSON 对象，键为原名称（必须逐字使用下面列出的名称），值为归一后的名称，不要输出其他文字。

                """ + String.join("\n", dimensions);
    }

    /**
     * 解析维度归一结果；模型没给出、给了空值或输出损坏时，该维度保持原名，排行照常计算，只是同义维度不合并。
     * @param output 模型正文
     * @param dimensions 待归一的维度名
     * @return 原名称到归一名称的映射，覆盖全部输入名称
     */
    public Map<String, String> parseDimensionMapping(String output, Collection<String> dimensions) {
        JSONObject parsed = extractObjectOrNull(output);
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String dimension : dimensions) {
            String normalized = parsed == null ? null : parsed.getString(dimension);
            mapping.put(dimension, normalized == null || normalized.isBlank() ? dimension : normalized.strip());
        }
        return mapping;
    }

    /**
     * 截出模型正文里的 JSON 对象；归一结果损坏不算报告失败，因此不抛出。
     * @param output 模型正文
     * @return JSON 对象；无法解析时为 null
     */
    private JSONObject extractObjectOrNull(String output) {
        try {
            return LLMJsonOutputParser.extractObject(output, "维度归一结果");
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
