package org.huangry.colorful.geo.application.diagnosis.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

/** 任务接口只展示冻结的非敏感配置，不返回渠道实体或认证密钥。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiagnosisTaskResponse(Long id, Long planId, String planName, Long projectId, String projectName,
        String status, ProgressResponse progress, List<ChannelProgressResponse> channels, JsonNode snapshot,
        LocalDateTime createdAt, LocalDateTime startedAt, LocalDateTime finishedAt) {
    /** 从采样结果实时聚合，结束数量为 succeeded + failed。 */
    public record ProgressResponse(long total, long pending, long running, long succeeded, long failed) {}
    /** 每个冻结渠道的进度，不使用当前渠道名称替换历史配置。 */
    public record ChannelProgressResponse(Long channelId, String channelName, ProgressResponse progress) {}
    /** 沿用管理后台从零开始的页码。 */
    public record PageResponse<T>(List<T> items, long total, int page, int size) {}
    /** 采样列表不携带大段正文与原始响应；详情查询才提供这些内容。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ResultResponse(Long id, Long taskId, int questionSortOrder, String questionText,
            JsonNode questionSnapshot, Long channelId, String channelName, JsonNode channelSnapshot,
            int repeatIndex, String status, String answerText, JsonNode citations, String modelName,
            JsonNode usageInfo, Long durationMs, String finishReason, String rawResponse, String errorMessage,
            LocalDateTime startedAt, LocalDateTime finishedAt) {}
}
