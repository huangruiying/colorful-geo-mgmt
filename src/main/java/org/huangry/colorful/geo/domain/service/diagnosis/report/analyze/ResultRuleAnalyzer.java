package org.huangry.colorful.geo.domain.service.diagnosis.report.analyze;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 诊断结果规则分析：按规则识别品牌提及、是否实际联网、引用来源归类，不调用大模型、不访问数据库。
 * <p>只依据诊断结果中已保存的回答、引用与原始响应，以及任务快照中的项目资料；不抓取网页内容。</p>
 */
@Component
public class ResultRuleAnalyzer {
    /** 来源归类：官网域名或其子域名。 */
    public static final String SOURCE_OWNED = "OWNED";
    /** 来源归类：与我方发布记录的公开链接一致。 */
    public static final String SOURCE_PUBLISHED = "PUBLISHED";
    /** 来源归类：标题提到目标的第三方内容。 */
    public static final String SOURCE_THIRD_PARTY_TARGET = "THIRD_PARTY_TARGET";
    /** 来源归类：标题提到预设竞品的内容。 */
    public static final String SOURCE_COMPETITOR = "COMPETITOR";
    /** 来源归类：无法归入以上类型。 */
    public static final String SOURCE_OTHER = "OTHER";

    /** 常见内容平台域名与名称对照，按域名后缀匹配；对照不上时以域名展示。 */
    private static final Map<String, String> PLATFORM_BY_DOMAIN = platformByDomain();

    /**
     * 判断文本是否提到任一名称，不区分大小写。
     * @param text 回答原文或来源标题，可为 null
     * @param names 目标或竞品的名称与别名，空白名称忽略
     * @return 提到任一名称时为 true
     */
    public boolean containsAnyName(String text, Collection<String> names) {
        if (text == null || names == null) {
            return false;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return names.stream().anyMatch(name -> name != null && !name.isBlank() && lower.contains(name.strip().toLowerCase(Locale.ROOT)));
    }

    /**
     * 判断回答是否实际联网：响应含搜索调用或检索结果，或已保存结构化引用。
     * <p>DeepSeek flash、文心普通对话等会静默不搜索，这类回答不参与引用分析。</p>
     * @param rawResponse 模型原始响应，可为 null
     * @param citations 已保存的引用 JSON 数组字符串，可为 null
     * @return 有联网证据时为 true
     */
    public boolean isSearchPerformed(String rawResponse, String citations) {
        // 1. 已有结构化引用即说明使用了检索来源
        if (citations != null && JSON.parse(citations) instanceof JSONArray array && !array.isEmpty()) {
            return true;
        }
        if (rawResponse == null || rawResponse.isBlank()) {
            return false;
        }
        // 2. Responses 协议的搜索调用，或千帆智能搜索、联网对话的检索结果
        Object parsed = JSON.parse(rawResponse);
        if (!(parsed instanceof JSONObject response)) {
            return false;
        }
        if (response.get("output") instanceof JSONArray output && output.stream()
                .anyMatch(item -> item instanceof JSONObject value && "web_search_call".equals(value.getString("type")))) {
            return true;
        }
        return nonEmptyArray(response.get("references")) || nonEmptyArray(response.get("search_results"));
    }

    /**
     * 对回答的引用逐条归类，保留原顺序，按网址去重。
     * <p>判断顺序：我方发布 → 自有域名 → 标题提到目标 → 标题提到竞品 → 其他；发布记录比域名更具体，因此优先。</p>
     * @param citations 已保存的引用 JSON 数组字符串，可为 null
     * @param project 任务快照中的项目资料，含 name、aliases、competitors、websiteUrl
     * @param publishedUrls 我方已公开发布内容的链接
     * @return 来源 JSON 数组：url、title、domain、platform、sourceType
     */
    public JSONArray classifySources(String citations, JSONObject project, Set<String> publishedUrls) {
        JSONArray sources = new JSONArray();
        if (citations == null || !(JSON.parse(citations) instanceof JSONArray array)) {
            return sources;
        }
        String ownedDomain = host(project.getString("websiteUrl"));
        List<String> targetNames = listTargetNames(project);
        List<String> competitors = convertToStringList(project.get("competitors"));
        Map<String, JSONObject> byUrl = new LinkedHashMap<>();
        for (Object value : array) {
            if (!(value instanceof JSONObject citation) || !(citation.get("url") instanceof String url) || url.isBlank()) {
                continue;
            }
            String title = citation.getString("title");
            String domain = host(url);
            String sourceType = classifySource(url.strip(), title, domain, ownedDomain, publishedUrls, targetNames, competitors);
            byUrl.putIfAbsent(url.strip(), new JSONObject(true)
                    .fluentPut("url", url.strip())
                    .fluentPut("title", title == null ? "" : title)
                    .fluentPut("domain", domain)
                    .fluentPut("platform", platform(domain))
                    .fluentPut("sourceType", sourceType));
        }
        sources.addAll(byUrl.values());
        return sources;
    }

    /**
     * 判断一条来源属于哪一类，按顺序命中即止：我方发布 → 官网 → 标题提到目标 → 标题提到竞品 → 其他。
     * @param url 来源网址（已去首尾空白）
     * @param title 来源标题，可为 null
     * @param domain 来源域名，可为 null
     * @param ownedDomain 项目官网域名，可为 null
     * @param publishedUrls 我方已公开发布内容的链接
     * @param targetNames 目标名称与别名
     * @param competitors 预设竞品名称
     * @return 来源类型，取值为本类的 SOURCE_* 常量
     */
    private String classifySource(String url, String title, String domain, String ownedDomain, Set<String> publishedUrls,
                                  List<String> targetNames, List<String> competitors) {
        if (publishedUrls.contains(url)) {
            return SOURCE_PUBLISHED;
        }
        // 官网包括其子域名，如官网 tuanyou.com 时 help.tuanyou.com 也算
        if (ownedDomain != null && domain != null && (domain.equals(ownedDomain) || domain.endsWith("." + ownedDomain))) {
            return SOURCE_OWNED;
        }
        if (containsAnyName(title, targetNames)) {
            return SOURCE_THIRD_PARTY_TARGET;
        }
        if (containsAnyName(title, competitors)) {
            return SOURCE_COMPETITOR;
        }
        return SOURCE_OTHER;
    }

    /**
     * @param project 任务快照中的项目资料
     * @return 目标名称与别名，去除空白项
     */
    public List<String> listTargetNames(JSONObject project) {
        List<String> names = new ArrayList<>();
        if (project.getString("name") != null && !project.getString("name").isBlank()) {
            names.add(project.getString("name").strip());
        }
        names.addAll(convertToStringList(project.get("aliases")));
        return names;
    }

    /**
     * @param value 快照中的字符串数组
     * @return 非空白字符串列表，非数组时为空列表
     */
    public List<String> convertToStringList(Object value) {
        List<String> items = new ArrayList<>();
        if (value instanceof JSONArray array) {
            for (Object item : array) {
                if (item instanceof String text && !text.isBlank()) {
                    items.add(text.strip());
                }
            }
        }
        return items;
    }

    /**
     * @param domain 来源域名，可为 null
     * @return 平台名称；对照不上时返回域名本身
     */
    private String platform(String domain) {
        if (domain == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : PLATFORM_BY_DOMAIN.entrySet()) {
            if (domain.equals(entry.getKey()) || domain.endsWith("." + entry.getKey())) {
                return entry.getValue();
            }
        }
        return domain;
    }

    /**
     * @param url 网址，可为 null
     * @return 小写主机名并去掉 www. 前缀；无法解析时为 null
     */
    private String host(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url.strip()).getHost();
            if (host == null) {
                return null;
            }
            host = host.toLowerCase(Locale.ROOT);
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (IllegalArgumentException exception) {
            // 引用中偶有不规范网址，无法解析时不归类域名，不影响其他来源
            return null;
        }
    }

    /**
     * @param value 响应字段
     * @return 字段为非空数组时为 true
     */
    private boolean nonEmptyArray(Object value) {
        return value instanceof JSONArray array && !array.isEmpty();
    }

    /**
     * 平台对照表，子域名精确的条目放在前面，保证 mp.weixin.qq.com 先于 qq.com 命中。
     * @return 有序的域名到平台名称映射
     */
    private static Map<String, String> platformByDomain() {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("mp.weixin.qq.com", "微信公众号");
        map.put("baijiahao.baidu.com", "百家号");
        map.put("zhidao.baidu.com", "百度知道");
        map.put("baike.baidu.com", "百度百科");
        map.put("zhihu.com", "知乎");
        map.put("toutiao.com", "今日头条");
        map.put("douyin.com", "抖音");
        map.put("xiaohongshu.com", "小红书");
        map.put("bilibili.com", "哔哩哔哩");
        map.put("weibo.com", "微博");
        map.put("csdn.net", "CSDN");
        map.put("jianshu.com", "简书");
        map.put("smzdm.com", "什么值得买");
        map.put("eastmoney.com", "东方财富");
        map.put("sohu.com", "搜狐");
        map.put("163.com", "网易");
        map.put("sina.com.cn", "新浪");
        map.put("qq.com", "腾讯");
        return map;
    }
}
