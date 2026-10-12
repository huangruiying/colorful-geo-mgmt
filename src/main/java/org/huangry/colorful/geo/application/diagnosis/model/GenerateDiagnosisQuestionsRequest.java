package org.huangry.colorful.geo.application.diagnosis.model;

/** 生成候选问题；生成渠道用于内部制题，与方案的被测渠道独立。 */
public record GenerateDiagnosisQuestionsRequest(Long projectId, String channelCode, Integer count, String language) {}
