package org.huangry.colorful.geo.application.diagnosis.model;

/** 手动发起一轮诊断；同一次提交重用 requestId，重新诊断使用新标识。 */
public record CreateDiagnosisTaskRequest(Long planId, String requestId) {}
