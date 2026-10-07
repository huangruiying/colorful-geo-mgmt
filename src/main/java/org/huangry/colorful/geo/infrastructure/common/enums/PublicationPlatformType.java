package org.huangry.colorful.geo.infrastructure.common.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 内容投放目标平台。
 *
 * <p>该枚举用于平台路由和任务记录，不表示每个平台均已具备官方自动发布能力。</p>
 *
 * @author huangry
 */
@Getter
@RequiredArgsConstructor
public enum PublicationPlatformType {

    /** 微信公众号，用于公众号图文内容投放。 */
    WECHAT_OFFICIAL_ACCOUNT("微信公众号"),

    /** 知乎，用于文章或问答内容投放。 */
    ZHIHU("知乎"),

    /** 掘金，用于技术文章草稿投放。 */
    JUEJIN("掘金"),

    /** 微博，用于社交内容草稿投放。 */
    WEIBO("微博"),

    /** CSDN，用于技术文章草稿投放。 */
    CSDN("CSDN 博客"),

    /** 语雀，用于知识文档草稿投放。 */
    YUQUE("语雀"),

    /** 豆瓣，用于文章草稿投放。 */
    DOUBAN("豆瓣"),

    /** 搜狐号，用于文章草稿投放。 */
    SOHU("搜狐号"),

    /** 雪球，用于财经内容草稿投放。 */
    XUEQIU("雪球"),

    /** 人人都是产品经理，用于产品文章草稿投放。 */
    WOSHIPM("人人都是产品经理"),

    /** 51CTO，用于技术文章草稿投放。 */
    CTO_51("51CTO"),

    /** 慕课网，用于技术文章草稿投放。 */
    IMOOC("慕课网"),

    /** 开源中国，用于技术文章草稿投放。 */
    OSCHINA("开源中国"),

    /** 思否，用于技术文章草稿投放。 */
    SEGMENTFAULT("思否"),

    /** 博客园，用于博客文章草稿投放。 */
    CNBLOGS("博客园"),

    /** 东方财富，用于财经内容草稿投放。 */
    EASTMONEY("东方财富"),

    /** 大鱼号，用于文章草稿投放。 */
    DAYU("大鱼号"),

    /** 简书，用于文章草稿投放。 */
    JIANSHU("简书"),

    /** 网易号，用于文章草稿投放。 */
    NETEASE("网易号"),

    /** 什么值得买，用于消费内容草稿投放。 */
    SMZDM("什么值得买"),

    /** 搜狐焦点，用于房产内容草稿投放。 */
    SOHU_FOCUS("搜狐焦点"),

    /** 一点资讯，用于文章草稿投放。 */
    YIDIAN("一点资讯"),

    /** 小红书，用于图文或视频笔记投放。 */
    XIAOHONGSHU("小红书"),

    /** 抖音，用于短视频或图文内容投放。 */
    DOUYIN("抖音"),

    /** 哔哩哔哩（B站），用于视频或专栏文章投放。 */
    BILIBILI("哔哩哔哩"),

    /** 今日头条，用于文章、微头条或视频内容投放。 */
    TOUTIAO("今日头条"),

    /** 百度百家号，用于文章、图文或视频内容投放。 */
    BAIJIAHAO("百度百家号");

    /** 平台展示名称；不影响用于路由和序列化的枚举名称。 */
    private final String displayName;
}
