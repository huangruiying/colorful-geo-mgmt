package org.huangry.colorful.geo.infrastructure.common.enums;

/**
 * 发布平台的登录结论，供登录状态查询接口使用；不代表平台具备公开发布能力。
 */
public enum PublicationLoginStatus {
	/** 扩展或平台明确确认已登录。 */
	LOGGED_IN,
	/** 扩展或平台明确确认未登录。 */
	NOT_LOGGED_IN,
	/** 查询失败、平台未返回或尚未接入登录查询。 */
	UNKNOWN
}
