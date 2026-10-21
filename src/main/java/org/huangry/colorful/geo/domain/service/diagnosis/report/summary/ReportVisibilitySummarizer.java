package org.huangry.colorful.geo.domain.service.diagnosis.report.summary;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.groupByChannel;

/**
 * 诊断报告 ② 可见度与推荐：推荐强度、保留意见、用户选择标准与顾虑排行。
 * <p>纯计算，不调用模型、不访问数据库；只统计判定成功的回答。</p>
 */
@Component
public class ReportVisibilitySummarizer {
    @Resource
    private ReportDimensionRanking reportDimensionRanking;

    /**
     * ② 可见度与推荐：推荐强度分布、保留意见与用户关注点排行；各模型核心指标在 ① 概览中计算。
     * @param judged 判定成功的回答
     * @param targetNames 目标名称与别名，保留意见只统计针对目标的
     * @param dimensionMapping 维度归一映射
     * @return strength、reservations、criteria、concerns 四个字段，直接并入 summary
     */
    public JSONObject buildVisibility(List<ReportAnswerFacts> judged, List<String> targetNames, Map<String, String> dimensionMapping) {
        return new JSONObject(true)
                .fluentPut("strength", listStrengthDistribution(judged))
                // 保留意见看所有问题里针对目标的缺点；选择标准与顾虑只看非品牌且没提到目标的回答，反映用户自己在意什么
                .fluentPut("reservations", reportDimensionRanking.listDimensionRanking(judged, fact -> true, "RESERVATION", targetNames, dimensionMapping))
                .fluentPut("criteria", reportDimensionRanking.listDimensionRanking(judged, fact -> fact.isNonBrandQuestion() && !fact.mentioned(),
                        "SELECTION_CRITERION", null, dimensionMapping))
                .fluentPut("concerns", reportDimensionRanking.listDimensionRanking(judged, fact -> fact.isNonBrandQuestion() && !fact.mentioned(),
                        "USER_CONCERN", null, dimensionMapping));
    }

    /**
     * 推荐强度分布：各模型在非品牌问题中提到目标的回答里，各强度的回答数。
     * <p>点名与对比问题本身带着目标品牌，回答必然提到目标，强度不能反映模型的主动推荐，因此不计入。</p>
     * @param judged 判定成功的回答
     * @return 每个模型一行，distribution 为强度到数量的映射
     */
    private JSONArray listStrengthDistribution(List<ReportAnswerFacts> judged) {
        JSONArray rows = new JSONArray();
        for (List<ReportAnswerFacts> channelFacts : groupByChannel(judged).values()) {
            Map<String, Long> distribution = new TreeMap<>(channelFacts.stream()
                    .filter(ReportAnswerFacts::isNonBrandQuestion)
                    .filter(fact -> fact.target() != null && fact.target().getString("strength") != null)
                    .collect(Collectors.groupingBy(fact -> fact.target().getString("strength"), Collectors.counting())));
            rows.add(new JSONObject(true)
                    .fluentPut("channelId", channelFacts.get(0).channelId())
                    .fluentPut("channelName", channelFacts.get(0).channelName())
                    .fluentPut("distribution", distribution));
        }
        return rows;
    }
}
