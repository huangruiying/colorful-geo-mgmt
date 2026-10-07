package org.huangry.colorful.geo.presentation.controller.model;

import org.huangry.colorful.geo.infrastructure.common.enums.PublicationPlatformType;

import java.util.List;

/**
 * 对一篇内容选择一个或多个平台创建草稿。
 *
 * @param publicationTitle 用户确认的发布标题
 * @param publicationContent 用户确认的发布正文
 * @param platformTypes 目标平台枚举列表
 */
public record PrePublishContentRequest(String publicationTitle, String publicationContent,
                                       List<PublicationPlatformType> platformTypes) {
}
