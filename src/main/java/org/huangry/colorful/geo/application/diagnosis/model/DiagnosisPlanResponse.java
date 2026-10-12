package org.huangry.colorful.geo.application.diagnosis.model;

import java.time.LocalDateTime;
import java.util.List;

/** 方案详情；渠道仅暴露关联 ID，不返回模型凭据。 */
public record DiagnosisPlanResponse(Long id, String name, Long projectId, String projectName,
                                    int repeatCount, List<Long> channelIds, List<DiagnosisQuestion> questions,
                                    LocalDateTime updatedAt) {}
