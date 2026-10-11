package org.huangry.colorful.geo.application.project.model;

import java.util.List;

/** 项目查询分页结果；items 为当前页档案，page 从零开始，total 为匹配总数。 */
public record ProjectInfoPage(List<ProjectInfoResponse> items, long total, int page, int size) {
}
