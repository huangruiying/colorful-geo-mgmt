package org.huangry.colorful.geo.application.diagnosis.model;

import java.util.List;

/** 保存已确认的方案，测试渠道必须显式指定，集合顺序即问题展示顺序。 */
public record SaveDiagnosisPlanRequest(String name, Long projectId, Integer repeatCount,
                                      List<Long> channelIds, List<DiagnosisQuestion> questions) {}
