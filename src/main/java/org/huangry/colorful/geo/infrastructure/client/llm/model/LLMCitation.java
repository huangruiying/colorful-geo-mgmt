package org.huangry.colorful.geo.infrastructure.client.llm.model;

/**
 * 大模型接口返回的结构化网页引用，不代表来源内容已经核验。
 *
 * @param title 来源标题；接口未提供时可为空字符串
 * @param url 来源链接；网页搜索策略会忽略空链接，不在此对象中校验可访问性
 * @param startIndex 接口返回的引用起始位置，未提供时为 null；保留接口原值
 * @param endIndex 接口返回的引用结束位置，未提供时为 null；保留接口原值
 * @param snippet 接口引用注解中的文本，未提供时为空字符串，不自动抓取网页摘要
 * @author huangry
 */
public record LLMCitation(String title, String url, Integer startIndex, Integer endIndex, String snippet) {
}
