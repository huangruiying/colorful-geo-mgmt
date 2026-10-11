package org.huangry.colorful.geo.application.llm.model;
import java.util.List;
/** 模型列表分页结果；不包含调用凭据。 */
public record LlmChannelPage(List<LlmChannelResponse> items, long total, int page, int size) {}
