package org.huangry.colorful.geo.regtest;

import org.huangry.colorful.geo.infrastructure.repository.entity.PlatformBrowserLoginEntity;

import java.util.List;

/**
 * 一个已走通平台的真实浏览器回归。由 {@link RegressionRunner} 统一驱动。
 */
public interface BrowserRegression {

    /** 平台枚举名，如 EASTMONEY。 */
    String platformKey();

    /** 中文展示名，如 东方财富。 */
    String displayName();

    /**
     * 对该平台执行全部节点回归。
     *
     * @param login  数据库登录态；为 null 表示未登录
     * @param title  用于创建的草稿标题（带自测标记，便于清理）
     * @param content 用于创建的草稿正文
     * @return 各节点结果，顺序即节点顺序
     */
    List<NodeResult> verify(PlatformBrowserLoginEntity login, String title, String content);
}
