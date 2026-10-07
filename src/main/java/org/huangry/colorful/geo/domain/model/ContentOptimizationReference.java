package org.huangry.colorful.geo.domain.model;

/**
 * 内容优化时允许使用的可核验参考资料。
 *
 * <p>统计、来源和引文策略只能基于该对象中的信息补充事实，不会主动联网检索或补造来源。</p>
 *
 * @param title 来源标题或发布主体，必填且不能仅包含空白
 * @param url 来源链接，必填；当前仅检查非空，不校验链接格式或自动抓取页面
 * @param excerpt 供模型使用的原文摘录，必填；调用方负责确认摘录与来源一致
 * @author huangry
 */
public record ContentOptimizationReference(String title, String url, String excerpt) {

    /** 校验参考资料包含标题、链接和摘录，不在构造时进行外部事实核验。 */
    public ContentOptimizationReference {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("内容优化参考资料标题不能为空");
        }
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("内容优化参考资料链接不能为空");
        }
        if (excerpt == null || excerpt.isBlank()) {
            throw new IllegalArgumentException("内容优化参考资料摘录不能为空");
        }
    }
}
