package org.huangry.colorful.geo.domain.service.diagnosis.report.summary;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.huangry.colorful.geo.domain.service.diagnosis.report.llm.AdvicePrompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.EXAMPLE_LIMIT;
import static org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportMetrics.groupByChannel;

/**
 * 诊断报告 ⑥ 规则发现的问题：从 ②–⑤ 已算好的结果里按固定阈值挑出问题，每个问题带编号、数字和证据。
 * <p>只由代码计算、结果稳定，不调用模型；模型写建议时只能引用这里的编号（见 AdvicePrompt），页面“看证据”展示的也是这里的内容。</p>
 */
@Component
public class ReportIssueSummarizer {
    /** 非品牌问题提及率低于此值记为“很少提到目标”。 */
    private static final double LOW_MENTION_RATE = 0.3;
    /** 每类维度问题（缺点、负面点、输掉的维度、内容差距）最多取几项。 */
    private static final int ISSUE_TOP_LIMIT = 3;
    /** 未设为竞品的产品出现在至少这么多条回答里才提示。 */
    private static final int NEW_COMPETITOR_MIN_MENTIONS = 2;

    /**
     * ⑥ 规则发现的问题：从 ②–⑤ 已算好的结果里按固定阈值挑出问题，每个问题带编号、数字和证据（原文或问题）。
     * <p>只由代码计算、结果稳定；模型写建议时只能引用这里的编号，页面“看证据”展示的也是这里的内容。</p>
     * @param summary 已组装好 ②–⑤ 各部分的汇总
     * @param judged 判定成功的回答，用于取问题类证据
     * @return 问题列表：id（P1、P2…）、type、title、channelId、channelName（模型相关时有值）、metrics、evidence
     */
    public JSONArray buildIssues(JSONObject summary, List<ReportAnswerFacts> judged) {
        JSONArray issues = new JSONArray();
        Map<Long, List<ReportAnswerFacts>> nonBrandByChannel = groupByChannel(judged.stream().filter(ReportAnswerFacts::isNonBrandQuestion).toList());
        // 1. 可见度：非品牌问题提及率过低；有问题在该模型上从未提到目标
        for (Object value : summary.getJSONArray("models")) {
            JSONObject model = (JSONObject) value;
            List<ReportAnswerFacts> nonBrand = nonBrandByChannel.getOrDefault(model.getLong("channelId"), List.of());
            Double mentionRate = model.getDouble("mentionRate");
            if (mentionRate != null && mentionRate < LOW_MENTION_RATE) {
                addIssue(issues, "LOW_MENTION", buildModelLabel(model) + " 在非品牌问题中很少提到目标", model,
                        List.of(buildMetric("提及率", mentionRate, true), buildMetric("推荐率", model.getDouble("recommendRate"), true),
                                buildMetric("非品牌回答", model.getIntValue("nonBrandAnswers"), false)),
                        listQuestionEvidence(nonBrand.stream().filter(fact -> !fact.mentioned()).toList()));
            }
            List<ReportAnswerFacts> missedQuestions = listMissedQuestions(nonBrand);
            if (!missedQuestions.isEmpty()) {
                addIssue(issues, "MISSED_QUESTIONS", buildModelLabel(model) + " 有 " + missedQuestions.size() + " 道非品牌问题从未提到目标", model,
                        List.of(buildMetric("问题覆盖率", model.getDouble("questionCoverage"), true)), listQuestionEvidence(missedQuestions));
            }
        }
        // 2. 竞品：同一模型上竞品推荐率高于目标
        JSONObject competitor = summary.getJSONObject("competitor");
        for (List<JSONObject> channelRows : groupRowsByChannel(competitor.getJSONArray("brands")).values()) {
            JSONObject target = channelRows.get(0);
            for (JSONObject rival : channelRows.subList(1, channelRows.size())) {
                if (target.getDouble("recommendRate") != null && rival.getDouble("recommendRate") != null
                        && rival.getDouble("recommendRate") > target.getDouble("recommendRate")) {
                    String rivalName = rival.getString("brandName");
                    List<ReportAnswerFacts> lost = nonBrandByChannel.getOrDefault(rival.getLong("channelId"), List.of()).stream()
                            .filter(fact -> fact.isBrandRecommended(rivalName) && !fact.isTargetRecommended()).toList();
                    addIssue(issues, "COMPETITOR_AHEAD", buildModelLabel(rival) + " 上 " + rivalName + " 的推荐率高于目标", rival,
                            List.of(buildMetric("目标推荐率", target.getDouble("recommendRate"), true), buildMetric(rivalName + " 推荐率", rival.getDouble("recommendRate"), true)),
                            listQuestionEvidence(lost));
                }
            }
        }
        // 3. 观点：被推荐时的缺点、负面评价、对比中输掉的维度，各取次数最多的几项
        addDimensionIssues(issues, "RESERVATION", "被推荐时常附带缺点：", summary.getJSONArray("reservations"), "count");
        addDimensionIssues(issues, "NEGATIVE_POINT", "点名问题中的负面评价：", summary.getJSONObject("brand").getJSONArray("negatives"), "count");
        List<JSONObject> losingDimensions = competitor.getJSONArray("dimensions").stream().map(JSONObject.class::cast)
                .filter(row -> row.getIntValue("lose") > row.getIntValue("win")).limit(ISSUE_TOP_LIMIT).toList();
        for (JSONObject row : losingDimensions) {
            addIssue(issues, "LOSING_DIMENSION", "对比问题中在「" + row.getString("dimension") + "」上输给竞品", null,
                    List.of(buildMetric("赢", row.getIntValue("win"), false), buildMetric("输", row.getIntValue("lose"), false), buildMetric("平", row.getIntValue("tie"), false)),
                    row.getJSONArray("examples"));
        }
        // 4. 认知准确性：回答中有与项目资料不符的描述
        JSONArray factErrors = summary.getJSONObject("brand").getJSONArray("factErrors");
        if (!factErrors.isEmpty()) {
            JSONArray evidence = new JSONArray();
            factErrors.stream().map(JSONObject.class::cast).limit(EXAMPLE_LIMIT).forEach(error -> evidence.add(new JSONObject(true)
                    .fluentPut("resultId", error.getLong("resultId"))
                    .fluentPut("channelName", error.getString("channelName"))
                    .fluentPut("brand", error.getString("content"))
                    .fluentPut("quote", error.getString("quote"))));
            addIssue(issues, "FACT_ERROR", "回答中有 " + factErrors.size() + " 条与项目资料不符的描述", null,
                    List.of(buildMetric("错误描述", factErrors.size(), false)), evidence);
        }
        // 5. 内容与来源：用户关心的标准没有我方内容被引用；某模型从不引用官网和我方发布内容
        List<JSONObject> gaps = summary.getJSONObject("source").getJSONArray("contentGaps").stream().map(JSONObject.class::cast)
                .filter(row -> row.getIntValue("ownCitedAnswers") == 0).limit(ISSUE_TOP_LIMIT).toList();
        for (JSONObject row : gaps) {
            addIssue(issues, "CONTENT_GAP", "用户关心「" + row.getString("dimension") + "」，相关回答从未引用我方内容", null,
                    List.of(buildMetric("提到的回答", row.getIntValue("answers"), false)), row.getJSONArray("examples"));
        }
        for (Object value : summary.getJSONObject("source").getJSONArray("models")) {
            JSONObject model = (JSONObject) value;
            if (model.getIntValue("withSources") > 0 && Double.valueOf(0).equals(model.getDouble("ownedRate"))
                    && Double.valueOf(0).equals(model.getDouble("publishedRate"))) {
                addIssue(issues, "OWN_SOURCE_UNUSED", buildModelLabel(model) + " 的回答从未引用官网或我方发布内容", model,
                        List.of(buildMetric("带来源回答", model.getIntValue("withSources"), false), buildMetric("来源覆盖率", model.getDouble("sourceCoverage"), true)),
                        new JSONArray());
            }
        }
        // 6. 竞品范围：回答里多次出现、但没设为竞品的同类产品
        for (Object value : competitor.getJSONArray("candidates")) {
            JSONObject candidate = (JSONObject) value;
            if (candidate.getIntValue("mentions") >= NEW_COMPETITOR_MIN_MENTIONS) {
                addIssue(issues, "NEW_COMPETITOR", "回答中多次出现未设为竞品的「" + candidate.getString("name") + "」", null,
                        List.of(buildMetric("出现回答数", candidate.getIntValue("mentions"), false), buildMetric("被推荐回答数", candidate.getIntValue("recommended"), false)),
                        new JSONArray());
            }
        }
        return issues;
    }

    /**
     * 把维度排行中次数最多的几项各记为一个问题，证据为排行自带的原文示例。
     * @param issues 问题列表
     * @param type 问题类型
     * @param titlePrefix 标题前缀，后接维度名
     * @param rows 维度排行（已按次数降序）
     * @param countKey 次数字段名
     */
    private void addDimensionIssues(JSONArray issues, String type, String titlePrefix, JSONArray rows, String countKey) {
        rows.stream().map(JSONObject.class::cast).limit(ISSUE_TOP_LIMIT).forEach(row -> addIssue(issues, type,
                titlePrefix + row.getString("dimension"), null, List.of(buildMetric("次数", row.getIntValue(countKey), false)), row.getJSONArray("examples")));
    }

    /**
     * 追加一个问题，编号按加入顺序为 P1、P2…
     * @param issues 问题列表
     * @param type 问题类型
     * @param title 一句话说明问题
     * @param channel 问题所属模型的行（含 channelId、channelName），与模型无关时为 null
     * @param metrics 支撑问题的数字
     * @param evidence 证据：resultId、channelName，以及 quote（回答原文）或 question（问题原文）
     */
    private void addIssue(JSONArray issues, String type, String title, JSONObject channel, List<JSONObject> metrics, JSONArray evidence) {
        issues.add(new JSONObject(true)
                .fluentPut("id", "P" + (issues.size() + 1))
                .fluentPut("type", type)
                .fluentPut("title", title)
                .fluentPut("channelId", channel == null ? null : channel.getLong("channelId"))
                .fluentPut("channelName", channel == null ? null : channel.getString("channelName"))
                .fluentPut("metrics", new JSONArray(new ArrayList<>(metrics)))
                .fluentPut("evidence", evidence));
    }

    /**

     * 组装问题下的一个支撑数字，页面按 rate 决定是否显示为百分比。
     * @param label 指标名称
     * @param value 指标值
     * @param rate 是否比率，页面按百分比展示
     * @return 问题的一个支撑数字
     */
    private JSONObject buildMetric(String label, Object value, boolean rate) {
        return new JSONObject(true)
                .fluentPut("label", label)
                .fluentPut("value", value)
                .fluentPut("rate", rate);
    }

    /**

     * 组装问题标题里的模型展示名。
     * @param model 带 channelName、modelName 的模型行
     * @return “渠道 · 模型”展示名，没有模型名时只用渠道名
     */
    private String buildModelLabel(JSONObject model) {
        return model.getString("modelName") == null ? model.getString("channelName") : model.getString("channelName") + " · " + model.getString("modelName");
    }

    /**

     * 找出某模型上所有回答都没提到目标的非品牌问题，作为“问题未覆盖”的证据。
     * @param nonBrand 某模型的非品牌回答
     * @return 从未提到目标的问题，每题取第一条回答，按问题序号排列
     */
    private List<ReportAnswerFacts> listMissedQuestions(List<ReportAnswerFacts> nonBrand) {
        return nonBrand.stream().collect(Collectors.groupingBy(ReportAnswerFacts::questionSortOrder, TreeMap::new, Collectors.toList()))
                .values().stream().filter(group -> group.stream().noneMatch(ReportAnswerFacts::mentioned)).map(group -> group.get(0)).toList();
    }

    /**

     * 把回答转成问题类证据，页面显示问题原文并可打开对应回答。
     * @param facts 作为证据的回答
     * @return 前 EXAMPLE_LIMIT 条回答的问题证据：resultId、channelName、question
     */
    private JSONArray listQuestionEvidence(List<ReportAnswerFacts> facts) {
        JSONArray evidence = new JSONArray();
        facts.stream().limit(EXAMPLE_LIMIT).forEach(fact -> evidence.add(new JSONObject(true)
                .fluentPut("resultId", fact.answer().result().getId())
                .fluentPut("channelName", fact.channelName())
                .fluentPut("question", fact.questionText())));
        return evidence;
    }

    /**

     * 把目标与竞品对比表按模型分组，每组第一行是目标，其余是竞品。
     * @param rows 带 channelId 的行，同一模型的行连续出现
     * @return 按 channelId 分组，保持原顺序
     */
    private Map<Long, List<JSONObject>> groupRowsByChannel(JSONArray rows) {
        return rows.stream().map(JSONObject.class::cast)
                .collect(Collectors.groupingBy(row -> row.getLong("channelId"), LinkedHashMap::new, Collectors.toList()));
    }
}
