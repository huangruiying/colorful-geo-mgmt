package org.huangry.colorful.geo.infrastructure.client.publish.model;

import org.huangry.colorful.geo.infrastructure.common.enums.PublicationLoginStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;

/**
 * 单个平台的登录状态展示对象；仅返回已接入发布策略的平台，不执行登录。
 *
 * @param platformType 平台标识
 * @param displayName 平台名称
 * @param status 登录结论
 * @param username 登录账号名；未登录或未知时为空
 */
public record PlatformLoginStatus(PublicationPlatformType platformType, String displayName,
		PublicationLoginStatus status, String username) { }
