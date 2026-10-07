package org.huangry.colorful.geo.infrastructure.client.publish.model;

import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;

/**
 * 页面可选择的已接入平台；不查询浏览器登录态，因此可用于快速打开平台选择窗口。
 *
 * @param platformType 平台枚举标识
 * @param displayName 中文或产品展示名
 */
public record PublicationPlatformOption(PublicationPlatformType platformType, String displayName) {
}
