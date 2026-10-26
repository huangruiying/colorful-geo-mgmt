package org.huangry.colorful.geo.application.project.model;

import java.util.Map;

/**
 * 项目文件导入结果，只包含新项目及实际写入数量，不返回完整文件或渠道资料。
 * @param projectId 新项目 ID，字符串避免浏览器整数精度损失
 * @param projectName 项目名称
 * @param counts 各文件分区写入数量，不包含历史问题的临时 ID 预留行
 */
public record ProjectImportResponse(String projectId, String projectName, Map<String, Integer> counts) { }
