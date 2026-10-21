package org.huangry.colorful.geo.domain.service.diagnosis.report.llm;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.stream.Collectors;

import static org.huangry.colorful.geo.infrastructure.client.llm.output.LLMJsonOutputParser.extractObject;
import static org.huangry.colorful.geo.infrastructure.client.llm.output.LLMJsonOutputParser.isAllowed;
import static org.huangry.colorful.geo.infrastructure.client.llm.output.LLMJsonOutputParser.listObjects;

/**
 * 诊断报告优化建议提示词：把规则发现的问题交给模型，让它挑重点写出主要发现与优化建议，并严格校验输出；本类不调用模型。
 * <p>整份报告调用一次，由后台执行器在汇总后调用。模型只能引用问题编号，引用不存在的编号的条目丢弃，防止编造结论。</p>
 */
@Component
public class AdvicePrompt {
    /** 主要发现、优化建议各自最多保留的条数。 */
    private static final int ITEM_LIMIT = 5;
    /** 建议类别：补内容空缺、完善已有内容、增加可信证据、纠正和统一信息、改善公开访问与检索、补充第三方覆盖。 */
    private static final Set<String> CATEGORIES = Set.of("FILL_CONTENT_GAP", "IMPROVE_CONTENT", "ADD_EVIDENCE",
            "CORRECT_INFORMATION", "IMPROVE_ACCESS", "ADD_THIRD_PARTY");
    /** 证据状态：回答明确表达、待验证推测。来源内容本系统不抓取核实，所以没有“来源已核实”。 */
    private static final Set<String> EVIDENCE_STATUSES = Set.of("ANSWER_STATED", "INFERRED");

    /**
     * 组装优化建议提示词。
     * @param project 任务快照中的项目资料，取名称与核心卖点
     * @param issues 规则发现的问题（id、title、channelName、metrics、evidence）
     * @return 发送给判定模型的完整提示词
     */
    public String buildPrompt(JSONObject project, JSONArray issues) {
        return """
                你是 GEO（生成式引擎优化）顾问。下面是对 AI 回答做诊断后，由程序按规则找出的问题，每个问题有编号、数字和回答原文证据。
                请只依据这些问题写出主要发现和优化建议，不要引入问题里没有的事实。严格输出一个 JSON 对象，不要输出其他文字，不要使用 Markdown 代码块。

                【目标】%s
                【核心卖点】%s
                【问题列表】
                %s

                【输出 JSON 格式】
                {
                  "findings": [{"content": "一句话结论", "issueIds": ["P1"]}],
                  "actions": [{"title": "建议标题", "category": "类别", "content": "具体做什么", "evidenceStatus": "ANSWER_STATED|INFERRED", "issueIds": ["P1"]}]
                }

                【规则】
                1. findings 写 3 到 5 条，概括最重要的问题；actions 写 3 到 5 条，按优先级从高到低排列，优先处理影响模型多、差距大的问题。
                2. 每条都必须在 issueIds 中列出依据的问题编号，只能使用上面出现的编号。
                3. category 只能是：FILL_CONTENT_GAP（补内容空缺）、IMPROVE_CONTENT（完善已有内容）、ADD_EVIDENCE（增加可信证据）、
                   CORRECT_INFORMATION（纠正和统一信息）、IMPROVE_ACCESS（改善公开访问与检索）、ADD_THIRD_PARTY（补充第三方覆盖）。
                4. evidenceStatus：结论直接来自回答原文用 ANSWER_STATED；原因或做法属于推测用 INFERRED。
                5. 如果某道题本身不适合推荐目标，可以直接说明，不必强行给出提升建议。
                """.formatted(project.getString("name"), project.getString("sellingPoints") == null ? "" : project.getString("sellingPoints"),
                JSON.toJSONString(issues));
    }

    /**
     * 解析并校验模型输出：只保留引用了已知问题编号的条目，类别、证据状态不在约定取值内的建议丢弃。
     * @param output 模型返回的正文，可带前后说明或代码块
     * @param issues 规则发现的问题，用于校验编号
     * @return findings（content、issueIds）与 actions（title、category、content、evidenceStatus、issueIds）
     * @throws IllegalArgumentException 输出不是有效 JSON 对象
     */
    public JSONObject parseAdvice(String output, JSONArray issues) {
        // 1. 截取最外层 JSON 对象，兼容模型在前后附带说明或代码块
        JSONObject root = extractObject(output, "优化建议");
        Set<String> knownIds = issues.stream().map(issue -> ((JSONObject) issue).getString("id")).collect(Collectors.toSet());
        // 2. 主要发现：内容不能为空，至少引用一个已知问题
        JSONArray findings = new JSONArray();
        for (JSONObject finding : listObjects(root.get("findings"))) {
            JSONArray issueIds = keepKnownIds(finding.get("issueIds"), knownIds);
            if (isBlank(finding.getString("content")) || issueIds.isEmpty() || findings.size() >= ITEM_LIMIT) {
                continue;
            }
            findings.add(new JSONObject(true).fluentPut("content", finding.getString("content").strip()).fluentPut("issueIds", issueIds));
        }
        // 3. 优化建议：除引用编号外，类别与证据状态也必须是约定取值，顺序即优先级
        JSONArray actions = new JSONArray();
        for (JSONObject action : listObjects(root.get("actions"))) {
            JSONArray issueIds = keepKnownIds(action.get("issueIds"), knownIds);
            if (isBlank(action.getString("title")) || issueIds.isEmpty() || !isAllowed(CATEGORIES, action.getString("category"))
                    || !isAllowed(EVIDENCE_STATUSES, action.getString("evidenceStatus")) || actions.size() >= ITEM_LIMIT) {
                continue;
            }
            actions.add(new JSONObject(true)
                    .fluentPut("title", action.getString("title").strip())
                    .fluentPut("category", action.getString("category"))
                    .fluentPut("content", action.getString("content") == null ? "" : action.getString("content").strip())
                    .fluentPut("evidenceStatus", action.getString("evidenceStatus"))
                    .fluentPut("issueIds", issueIds));
        }
        return new JSONObject(true).fluentPut("findings", findings).fluentPut("actions", actions);
    }

    /**
     * @param value 模型给出的编号数组
     * @param knownIds 规则发现的问题编号
     * @return 去重后、只含已知编号的数组；非数组时为空
     */
    private JSONArray keepKnownIds(Object value, Set<String> knownIds) {
        JSONArray kept = new JSONArray();
        if (value instanceof JSONArray ids) {
            ids.stream().filter(String.class::isInstance).map(String.class::cast).map(String::strip)
                    .filter(knownIds::contains).distinct().forEach(kept::add);
        }
        return kept;
    }

    /**
     * @param text 文本
     * @return null 或只有空白时为 true
     */
    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
