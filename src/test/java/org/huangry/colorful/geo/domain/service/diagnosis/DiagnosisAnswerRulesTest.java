package org.huangry.colorful.geo.domain.service.diagnosis;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 规则处理：提及识别、是否实际联网、来源归类顺序与平台对照。 */
class DiagnosisAnswerRulesTest {
    private final DiagnosisAnswerRules rules = new DiagnosisAnswerRules();
    private final JSONObject project = JSON.parseObject("""
            {"name":"团油","aliases":["能链团油","Newlink"],"competitors":["易加油"],"websiteUrl":"https://www.tuanyou.net/"}""");

    /** 名称或别名不区分大小写命中即为提及，空白名称不参与匹配。 */
    @Test
    void shouldMatchNamesIgnoringCase() {
        assertTrue(rules.mentions("推荐 NEWLINK 的产品", rules.targetNames(project)));
        assertFalse(rules.mentions("推荐易加油", rules.targetNames(project)));
        assertFalse(rules.mentions("任意文本", List.of(" ")));
        assertFalse(rules.mentions(null, rules.targetNames(project)));
    }

    /** 有引用、搜索调用或检索结果即为实际联网；DeepSeek flash 这类静默不搜的响应为未联网。 */
    @Test
    void shouldDetectSearchFromCitationsOrRawResponse() {
        assertTrue(rules.searchPerformed(null, "[{\"url\":\"https://a.com\"}]"));
        assertTrue(rules.searchPerformed("{\"output\":[{\"type\":\"reasoning\"},{\"type\":\"web_search_call\"}]}", "[]"));
        assertTrue(rules.searchPerformed("{\"references\":[{\"url\":\"https://a.com\"}],\"choices\":[]}", "[]"));
        assertFalse(rules.searchPerformed("{\"output\":[{\"type\":\"reasoning\"},{\"type\":\"message\"}]}", "[]"));
        assertFalse(rules.searchPerformed(null, null));
    }

    /** 我方发布优先于自有域名，其次标题提到目标、竞品；按网址去重并对照平台名称。 */
    @Test
    void shouldClassifySourcesInPriorityOrder() {
        String citations = """
                [{"url":"https://www.tuanyou.net/jc/13.html","title":"团油教程"},
                 {"url":"https://m.tuanyou.net/news","title":"新闻"},
                 {"url":"https://zhuanlan.zhihu.com/p/1","title":"能链团油真实体验"},
                 {"url":"https://baijiahao.baidu.com/s?id=2","title":"易加油怎么样"},
                 {"url":"https://mp.weixin.qq.com/s/3","title":"加油省钱指南"},
                 {"url":"https://zhuanlan.zhihu.com/p/1","title":"重复"},
                 {"url":"","title":"空地址"}]""";

        var sources = rules.classifySources(citations, project, Set.of("https://www.tuanyou.net/jc/13.html"));

        assertEquals(List.of("PUBLISHED", "OWNED", "THIRD_PARTY_TARGET", "COMPETITOR", "OTHER"),
                sources.stream().map(source -> ((JSONObject) source).getString("sourceType")).toList());
        assertEquals(List.of("tuanyou.net", "m.tuanyou.net", "知乎", "百家号", "微信公众号"),
                sources.stream().map(source -> ((JSONObject) source).getString("platform")).toList());
    }
}
