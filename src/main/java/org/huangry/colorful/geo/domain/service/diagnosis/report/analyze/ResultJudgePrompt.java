package org.huangry.colorful.geo.domain.service.diagnosis.report.analyze;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisBrandSentiment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.huangry.colorful.geo.infrastructure.client.llm.output.LLMJsonOutputParser.extractObject;
import static org.huangry.colorful.geo.infrastructure.client.llm.output.LLMJsonOutputParser.isAllowed;
import static org.huangry.colorful.geo.infrastructure.client.llm.output.LLMJsonOutputParser.listObjects;

/**
 * 诊断结果分析提示词：组装模型判定提示词，并严格校验模型返回的 JSON；本类不调用模型。
 * <p>一条回答调用一次，按问题分组只填写适用部分；不调用模型、不访问数据库，调用由后台执行器完成。
 * 原文片段必须逐字出现在回答中，校验不通过的观点丢弃，防止判定模型编造。</p>
 */
@Component
public class ResultJudgePrompt {
    /** 判定提示词版本；提示词或输出格式变化时递增，记录在报告上说明判定口径。 */
    public static final String PROMPT_VERSION = "v1";
    /** 观点概括与原文片段的保存长度上限。 */
    private static final int TEXT_LIMIT = 300;
    /** 品牌类型：目标、预设竞品、回答中新出现的同类产品。 */
    private static final Set<String> BRAND_TYPES = Set.of("TARGET", "COMPETITOR", "CANDIDATE");
    /** 推荐强度：首推、强推、并列、顺带一提、附带缺点、明确不推荐。 */
    private static final Set<String> STRENGTHS = Set.of("FIRST_CHOICE", "STRONG", "PARALLEL", "PASSING", "WITH_RESERVATION", "NOT_RECOMMENDED");
    /** 观点类型，按问题分组决定适用范围，见提示词说明。 */
    private static final Set<String> POINT_TYPES = Set.of("SELECTION_CRITERION", "USER_CONCERN", "RECOMMEND_REASON", "RESERVATION",
            "POSITIVE", "NEGATIVE", "FACT_ERROR", "SELLING_POINT_COVERED", "DIMENSION_RESULT");
    /** 维度胜负结果，仅 DIMENSION_RESULT 使用。 */
    private static final Set<String> OUTCOMES = Set.of("WIN", "LOSE", "TIE");
    /** 点名目标的问题分组，只有它判定品牌情感与描述准确性。 */
    private static final String BRAND_SCOPE = "BRAND";

    @Resource
    private ResultRuleAnalyzer ruleAnalyzer;

    /**
     * 组装一条回答的判定提示词。
     * @param project 任务快照中的项目资料
     * @param question 诊断结果中的问题快照，含 text、brandScope
     * @param answer 被判定的回答原文
     * @return 发送给判定模型的完整提示词
     */
    public String buildPrompt(JSONObject project, JSONObject question, String answer) {
        String brandScope = question.getString("brandScope");
        StringBuilder prompt = new StringBuilder("""
                你是 GEO（生成式引擎优化）分析助手。请只依据下面的“回答原文”做判定，不要使用你自己的知识评价任何品牌。
                严格输出一个 JSON 对象，不要输出任何其他文字，不要使用 Markdown 代码块。

                """);
        // 1. 背景：目标、别名、竞品与问题，点名问题额外附项目资料用于判断描述准确性
        prompt.append("【目标】").append(project.getString("name")).append('\n');
        prompt.append("【目标别名】").append(String.join("、", ruleAnalyzer.convertToStringList(project.get("aliases")))).append('\n');
        prompt.append("【预设竞品】").append(String.join("、", ruleAnalyzer.convertToStringList(project.get("competitors")))).append('\n');
        prompt.append("【问题分组】").append(brandScope).append("（NON_BRAND=未点名目标的品类或场景问题；BRAND=直接询问目标；COMPARISON=目标与竞品对比）\n");
        prompt.append("【用户问题】").append(question.getString("text")).append('\n');
        if (BRAND_SCOPE.equals(brandScope)) {
            prompt.append("【项目资料，仅用于判断回答对目标的描述是否准确】\n")
                    .append("核心功能：").append(nullToEmpty(project.getString("coreFeatures"))).append('\n')
                    .append("核心卖点：").append(nullToEmpty(project.getString("sellingPoints"))).append('\n')
                    .append("价格说明：").append(nullToEmpty(project.getString("pricing"))).append('\n')
                    .append("目标用户：").append(nullToEmpty(project.getString("targetAudience"))).append('\n');
        }
        prompt.append("【回答原文】\n").append(answer).append("\n\n");
        // 2. 输出格式与各字段适用范围
        prompt.append("""
                【输出 JSON 格式】
                {
                  "brands": [{"name": "产品名", "type": "TARGET|COMPETITOR|CANDIDATE", "recommended": true, "rank": 1, "strength": "FIRST_CHOICE|STRONG|PARALLEL|PASSING|WITH_RESERVATION|NOT_RECOMMENDED"}],
                  "brandSentiment": "POSITIVE|NEUTRAL|NEGATIVE",
                  "descriptionAccurate": true,
                  "points": [{"type": "类型", "brand": "针对的产品名或null", "dimension": "简短维度名如价格、油品质量", "outcome": "WIN|LOSE|TIE或null", "content": "一句话概括", "quote": "回答中的原文片段"}]
                }

                【规则】
                1. brands：列出回答中出现的每个产品或品牌。目标及其别名记为 TARGET；预设竞品记为 COMPETITOR，name 必须逐字使用【预设竞品】中的名称（回答用简称时也换成列表中的名称）；
                   回答中出现的其他同类产品记为 CANDIDATE。同一产品只输出一次。
                   rank 为回答中的推荐顺序（从 1 开始），没有明确顺序时为 null。strength 含义：FIRST_CHOICE 首推，STRONG 强烈推荐，PARALLEL 与其他并列推荐，
                   PASSING 顺带一提，WITH_RESERVATION 推荐但附带缺点，NOT_RECOMMENDED 明确不推荐。回答没有提到目标时，brands 中不要出现 TARGET。
                2. brandSentiment 与 descriptionAccurate 仅在问题分组为 BRAND 时填写，其他分组填 null。
                   descriptionAccurate 只依据上面的项目资料判断，资料未涉及的内容不判定对错；有明确错误时为 false 并在 points 中给出 FACT_ERROR。
                3. points 的 type 按问题分组使用：
                   - NON_BRAND：SELECTION_CRITERION（回答强调的选择标准）、USER_CONCERN（回答提醒的顾虑或风险）
                   - 有产品被推荐时：RECOMMEND_REASON（推荐理由，brand 填对应产品）、RESERVATION（推荐时附带的缺点，brand 填对应产品）
                   - BRAND：POSITIVE、NEGATIVE（对目标的正面点与负面点）、FACT_ERROR（与项目资料不符的描述）、SELLING_POINT_COVERED（回答提到的核心卖点）
                   - COMPARISON：DIMENSION_RESULT（目标在某个比较维度上相对竞品的结果，outcome 填 WIN/LOSE/TIE，brand 填对比的竞品）
                4. quote 摘取回答中最能支撑该观点的一句原文（不超过 80 字），逐字照抄，不得改写，不要跨段拼接多处内容；找不到原文依据的观点不要输出。
                5. 没有适用内容的数组输出 []。
                """);
        return prompt.toString();
    }

    /**
     * 解析并校验判定模型的输出。
     * @param output 判定模型返回的正文，可包含 Markdown 代码块包裹
     * @param answer 被判定的回答原文，用于校验原文片段
     * @param brandScope 问题分组，非 BRAND 时丢弃品牌情感与描述准确性
     * @param project 任务快照中的项目资料，用于统一目标与竞品名称
     * @return 校验后的判定结果
     * @throws IllegalArgumentException 输出不是有效 JSON 对象
     */
    public AnalysisResult parseAnalysisResult(String output, String answer, String brandScope, JSONObject project) {
        // 1. 截取最外层 JSON 对象，兼容模型在前后附带说明或代码块
        JSONObject root = extractObject(output, "判定结果");
        String normalizedAnswer = normalize(answer);
        // 2. 品牌：类型与强度必须是约定取值，名称为空的条目丢弃；名称统一后同一产品只保留首次出现
        JSONArray brands = new JSONArray();
        Set<String> seen = new HashSet<>();
        for (JSONObject brand : listObjects(root.get("brands"))) {
            String type = brand.getString("type");
            if (brand.getString("name") == null || brand.getString("name").isBlank() || !isAllowed(BRAND_TYPES, type)) {
                continue;
            }
            // 目标统一为项目名；竞品与待确认产品能对应预设的统一为预设竞品，对不上的为待确认产品，保证按竞品统计时不被不同写法拆散
            String name = brand.getString("name").strip();
            if ("TARGET".equals(type)) {
                name = project.getString("name");
            } else {
                String preset = presetCompetitor(name, ruleAnalyzer.convertToStringList(project.get("competitors")));
                type = preset == null ? "CANDIDATE" : "COMPETITOR";
                name = preset == null ? name : preset;
            }
            if (!seen.add(name)) {
                continue;
            }
            String strength = isAllowed(STRENGTHS, brand.getString("strength")) ? brand.getString("strength") : null;
            Integer rank = brand.get("rank") instanceof Integer value && value > 0 ? value : null;
            brands.add(new JSONObject(true)
                    .fluentPut("name", name.strip())
                    .fluentPut("type", type)
                    .fluentPut("recommended", Boolean.TRUE.equals(brand.getBoolean("recommended")))
                    .fluentPut("rank", rank)
                    .fluentPut("strength", strength));
        }
        // 3. 观点：类型须在约定范围，原文片段必须出现在回答中，否则视为编造并丢弃
        JSONArray points = new JSONArray();
        for (JSONObject point : listObjects(root.get("points"))) {
            String type = point.getString("type");
            String quote = point.getString("quote");
            if (!isAllowed(POINT_TYPES, type) || quote == null || quote.isBlank() || !normalizedAnswer.contains(normalize(quote))) {
                continue;
            }
            String outcome = "DIMENSION_RESULT".equals(type) && isAllowed(OUTCOMES, point.getString("outcome")) ? point.getString("outcome") : null;
            if ("DIMENSION_RESULT".equals(type) && outcome == null) {
                continue;
            }
            points.add(new JSONObject(true)
                    .fluentPut("type", type)
                    .fluentPut("brand", blankToNull(point.getString("brand")))
                    .fluentPut("dimension", blankToNull(point.getString("dimension")))
                    .fluentPut("outcome", outcome)
                    .fluentPut("content", truncate(point.getString("content")))
                    .fluentPut("quote", truncate(quote.strip())));
        }
        // 4. 品牌情感与描述准确性只对点名问题有效，其他分组即使模型填写也丢弃
        boolean brandQuestion = BRAND_SCOPE.equals(brandScope);
        String sentiment = brandQuestion && Arrays.stream(DiagnosisBrandSentiment.values()).map(Enum::name)
                .collect(Collectors.toSet()).contains(root.getString("brandSentiment")) ? root.getString("brandSentiment") : null;
        Boolean accurate = brandQuestion && root.get("descriptionAccurate") instanceof Boolean value ? value : null;
        return new AnalysisResult(brands, points, sentiment, accurate);
    }

    /**
     * 把竞品名称对应到预设竞品：完全一致优先，其次互相包含（处理简称与全称，如“支付宝”与“支付宝加油服务”）。
     * @param name 判定给出的竞品名称
     * @param competitors 预设竞品名称
     * @return 对应的预设名称，对不上时为 null
     */
    private String presetCompetitor(String name, List<String> competitors) {
        for (String competitor : competitors) {
            if (competitor.equalsIgnoreCase(name)) {
                return competitor;
            }
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String competitor : competitors) {
            String presetLower = competitor.toLowerCase(Locale.ROOT);
            if (presetLower.contains(lower) || lower.contains(presetLower)) {
                return competitor;
            }
        }
        return null;
    }

    /**
     * 比较原文片段前统一格式：去掉空白、Markdown 格式符号和字面量换行符。
     * <p>模型引用时常省略加粗、列表、标题等符号或把换行写成 \\n，这些只是格式差异；跨段拼接仍会对不上并被丢弃。</p>
     * @param text 文本
     * @return 只保留正文字符的文本
     */
    private String normalize(String text) {
        return text == null ? "" : text.replace("\\n", "").replaceAll("[\\s*#>`|_~\\-]+", "");
    }

    /**
     * @param text 概括或原文
     * @return 截断到保存上限的文本，null 保持 null
     */
    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= TEXT_LIMIT ? text : text.substring(0, TEXT_LIMIT);
    }

    /**
     * @param text 文本
     * @return 去除首尾空白，空白及字面量 null 转为 null
     */
    private String blankToNull(String text) {
        return text == null || text.isBlank() || "null".equalsIgnoreCase(text.strip()) ? null : text.strip();
    }

    /**
     * @param text 文本
     * @return null 转为空字符串
     */
    private String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /**
     * 校验后的判定结果。
     * @param brands 品牌数组：name、type、recommended、rank、strength
     * @param points 观点数组：type、brand、dimension、outcome、content、quote
     * @param brandSentiment 品牌情感枚举名称，仅点名问题有值
     * @param descriptionAccurate 描述是否准确，仅点名问题有值
     */
    public record AnalysisResult(JSONArray brands, JSONArray points, String brandSentiment, Boolean descriptionAccurate) {
    }
}
