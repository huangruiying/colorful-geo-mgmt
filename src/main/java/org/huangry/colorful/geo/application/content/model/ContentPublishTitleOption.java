package org.huangry.colorful.geo.application.content.model;

/**
 * 「发布记录」页文章筛选下拉框的一个选项，由 GET content-publish/titles 返回，每篇有发布记录的文章一项。
 * <p>页面用标题、全拼、首字母三者做模糊匹配；选中后按文章 ID 筛选发布记录，不按标题文字筛选。</p>
 *
 * @param contentOptimizationRecordId 文章（内容优化记录）ID
 * @param title                       该文章最新一条发布记录的标题快照
 * @param pinyin                      标题全拼，小写无声调，多音字取第一个读音
 * @param pinyinInitials              标题拼音首字母，小写
 */
public record ContentPublishTitleOption(long contentOptimizationRecordId, String title, String pinyin,
                                        String pinyinInitials) {
}
