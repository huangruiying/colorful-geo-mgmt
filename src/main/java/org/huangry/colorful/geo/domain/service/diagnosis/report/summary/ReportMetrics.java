package org.huangry.colorful.geo.domain.service.diagnosis.report.summary;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService.ReportAnswer;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskResultAnalysisStatus;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * 诊断报告各页签共用的计算口径：比率保留 4 位小数、按模型分组、解析判定观点、排行截断。
 * <p>无状态的静态方法，只服务诊断报告汇总，不放到通用工具类。</p>
 */
public final class ReportMetrics {
    /** 每个维度或问题保留的原文示例数量。 */
    public static final int EXAMPLE_LIMIT = 3;
    /** 维度排行、清单类结果保留的条目数量。 */
    public static final int RANKING_LIMIT = 20;

    private ReportMetrics() {
    }

    /**
     * 按被测模型（渠道）分组，各页签“每个模型一行”的统计都从这里开始。
     * @param facts 回答事实
     * @return 按渠道分组，保持首次出现顺序
     */
    public static Map<Long, List<ReportAnswerFacts>> groupByChannel(List<ReportAnswerFacts> facts) {
        return facts.stream().collect(Collectors.groupingBy(ReportAnswerFacts::channelId, LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * 解析一条回答的判定观点；判定失败的回答没有观点。
     * @param answer 一条回答
     * @return 判定成功时的观点列表，否则为空
     */
    public static List<JSONObject> listPoints(ReportAnswer answer) {
        if (!DiagnosisTaskResultAnalysisStatus.SUCCEEDED.name().equals(answer.analysis().getStatus()) || answer.analysis().getPoints() == null) {
            return List.of();
        }
        List<JSONObject> points = new ArrayList<>();
        for (Object value : JSON.parseArray(answer.analysis().getPoints())) {
            if (value instanceof JSONObject point) {
                points.add(point);
            }
        }
        return points;
    }

    /**
     * 统计满足条件的条目数，各页签“多少条回答提到了、推荐了”都用它。
     * @param items 待统计的条目
     * @param condition 计数条件
     * @return 满足条件的条目数
     */
    public static <T> long countMatching(Collection<T> items, Predicate<? super T> condition) {
        return items.stream().filter(condition).count();
    }

    /**
     * 计算比率，全报告统一保留 4 位小数；分母为 0 时返回 null，页面显示为“—”，不当作 0%。
     * @param numerator 分子
     * @param denominator 分母
     * @return 保留 4 位小数的比率，分母为 0 时为 null
     */
    public static Double calculateRate(long numerator, long denominator) {
        return denominator == 0 ? null : round((double) numerator / denominator);
    }

    /**
     * 四舍五入保留 4 位小数。
     * @param value 数值
     * @return 保留 4 位小数
     */
    public static double round(double value) {
        return Math.round(value * 10000) / 10000.0;
    }

    /**
     * 按某个整数字段降序排列并截取前 RANKING_LIMIT 行，用于各类排行与清单。
     * @param rows 待排序的行
     * @param key 排序用的整数字段
     * @return 按该字段降序的前 RANKING_LIMIT 行
     */
    public static JSONArray sortByIntDesc(Collection<JSONObject> rows, String key) {
        JSONArray sorted = new JSONArray();
        rows.stream().sorted((left, right) -> Integer.compare(right.getIntValue(key), left.getIntValue(key)))
                .limit(RANKING_LIMIT).forEach(sorted::add);
        return sorted;
    }
}
