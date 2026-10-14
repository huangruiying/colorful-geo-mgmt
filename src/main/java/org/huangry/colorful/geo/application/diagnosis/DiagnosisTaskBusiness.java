package org.huangry.colorful.geo.application.diagnosis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.application.diagnosis.model.CreateDiagnosisTaskRequest;
import org.huangry.colorful.geo.application.diagnosis.model.DiagnosisTaskResponse;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskService;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisPlanExecRecordDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisPlanExecRecordResultDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanExecRecordEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanExecRecordResultEntity;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.*;

/** 任务创建、分页与结果展示编排；响应显式映射，列表不携带大段回答。 */
@Service
public class DiagnosisTaskBusiness {
    @Resource
    private GeoDiagnosisTaskService service;
    @Resource
    private GeoDiagnosisPlanExecRecordDao taskDao;
    @Resource
    private GeoDiagnosisPlanExecRecordResultDao resultDao;
    @Resource
    private DiagnosisTaskSnapshots snapshots;

    /**
     * 并发创建冲突在原事务回滚后回读，避免在已失败事务中继续写库。
     * @param request 方案与提交标识
     * @return 已持久化任务
     */
    public DiagnosisTaskResponse create(CreateDiagnosisTaskRequest request) {
        GeoDiagnosisPlanExecRecordEntity task;
        try {
            task = service.create(request);
        } catch (DuplicateKeyException exception) {
            task = request == null ? null : service.findByRequest(request.requestId());
            if (task == null) {
                throw exception;
            }
            service.requireSamePlan(task, request.planId());
        }
        return response(task, true);
    }

    /**
     * 按来源方案与任务状态分页，页码从零开始。
     * @param planId 可选方案
     * @param status 可选任务状态
     * @param page 页码
     * @param size 每页数量
     * @return 摘要页
     */
    public DiagnosisTaskResponse.PageResponse<DiagnosisTaskResponse> list(Long planId, String status, int page, int size) {
        validatePage(page, size);
        validateStatus(status, true);
        LambdaQueryWrapper<GeoDiagnosisPlanExecRecordEntity> query = new LambdaQueryWrapper<GeoDiagnosisPlanExecRecordEntity>()
                .eq(planId != null, GeoDiagnosisPlanExecRecordEntity::getPlanId, planId)
                .eq(status != null && !status.isBlank(), GeoDiagnosisPlanExecRecordEntity::getStatus, status);
        long total = taskDao.selectCount(query);
        query.orderByDesc(GeoDiagnosisPlanExecRecordEntity::getId).last("LIMIT " + size + " OFFSET " + (long) page * size);
        var items = taskDao.selectList(query).stream().map(task -> response(task, false)).toList();
        return new DiagnosisTaskResponse.PageResponse<>(items, total, page, size);
    }

    /**
     * 详情采用冻结名称与配置，编辑方案不改变历史展示。
     * @param taskId 任务 ID
     * @return 详情及各渠道进度
     */
    public DiagnosisTaskResponse detail(long taskId) {
        return response(requireTask(taskId), true);
    }

    /**
     * 按状态和渠道分页采样，正文与原始响应不进入列表查询。
     * @param taskId 任务 ID
     * @param status 可选采样状态
     * @param channelId 可选渠道
     * @param page 页码
     * @param size 每页数量
     * @return 采样摘要页
     */
    public DiagnosisTaskResponse.PageResponse<DiagnosisTaskResponse.ResultResponse> results(long taskId, String status, Long channelId, int page, int size) {
        requireTask(taskId);
        validatePage(page, size);
        validateStatus(status, false);
        LambdaQueryWrapper<GeoDiagnosisPlanExecRecordResultEntity> query = new LambdaQueryWrapper<GeoDiagnosisPlanExecRecordResultEntity>()
                .eq(GeoDiagnosisPlanExecRecordResultEntity::getExecRecordId, taskId)
                .eq(status != null && !status.isBlank(), GeoDiagnosisPlanExecRecordResultEntity::getStatus, status)
                .eq(channelId != null, GeoDiagnosisPlanExecRecordResultEntity::getChannelId, channelId);
        long total = resultDao.selectCount(query);
        query.select(GeoDiagnosisPlanExecRecordResultEntity::getId, GeoDiagnosisPlanExecRecordResultEntity::getExecRecordId,
                GeoDiagnosisPlanExecRecordResultEntity::getQuestionSortOrder, GeoDiagnosisPlanExecRecordResultEntity::getQuestionSnapshot,
                GeoDiagnosisPlanExecRecordResultEntity::getChannelId, GeoDiagnosisPlanExecRecordResultEntity::getChannelSnapshot,
                GeoDiagnosisPlanExecRecordResultEntity::getRepeatIndex, GeoDiagnosisPlanExecRecordResultEntity::getStatus,
                GeoDiagnosisPlanExecRecordResultEntity::getDurationMs, GeoDiagnosisPlanExecRecordResultEntity::getErrorMessage)
                .orderByAsc(GeoDiagnosisPlanExecRecordResultEntity::getQuestionSortOrder)
                .orderByAsc(GeoDiagnosisPlanExecRecordResultEntity::getChannelId)
                .orderByAsc(GeoDiagnosisPlanExecRecordResultEntity::getRepeatIndex)
                .last("LIMIT " + size + " OFFSET " + (long) page * size);
        var items = resultDao.selectList(query).stream().map(this::resultResponse).toList();
        return new DiagnosisTaskResponse.PageResponse<>(items, total, page, size);
    }

    /**
     * 回答必须属于 URL 指定任务，防止串读其他任务结果。
     * @param taskId 任务 ID
     * @param resultId 采样 ID
     * @return 独立回答详情
     */
    public DiagnosisTaskResponse.ResultResponse result(long taskId, long resultId) {
        requireTask(taskId);
        GeoDiagnosisPlanExecRecordResultEntity row = resultDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisPlanExecRecordResultEntity>()
                .eq(GeoDiagnosisPlanExecRecordResultEntity::getId, resultId)
                .eq(GeoDiagnosisPlanExecRecordResultEntity::getExecRecordId, taskId));
        if (row == null) {
            throw new NoSuchElementException("诊断回答不存在或不属于该任务");
        }
        return resultResponse(row);
    }

    /**
     * 拒绝不存在或非法的任务标识。
     * @param taskId 任务 ID
     * @return 持久化任务
     */
    private GeoDiagnosisPlanExecRecordEntity requireTask(long taskId) {
        GeoDiagnosisPlanExecRecordEntity task = taskId > 0 ? taskDao.selectById(taskId) : null;
        if (task == null) {
            throw new NoSuchElementException("诊断任务不存在");
        }
        return task;
    }

    /**
     * 从结果聚合本轮和各渠道统计，完整快照只在详情返回。
     * @param task 持久化任务
     * @param detail 是否返回完整快照
     * @return 安全响应
     */
    private DiagnosisTaskResponse response(GeoDiagnosisPlanExecRecordEntity task, boolean detail) {
        JsonNode snapshot = snapshots.read(task.getPlanSnapshot());
        Map<Long, long[]> counts = new LinkedHashMap<>();
        long[] total = new long[5];
        for (Map<String, Object> row : resultDao.countStatuses(task.getId())) {
            long channelId = ((Number) row.get("channel_id")).longValue();
            long count = ((Number) row.get("sample_count")).longValue();
            String status = (String) row.get("status");
            int index = switch (status) {
                case "PENDING" -> 1;
                case "RUNNING" -> 2;
                case "SUCCEEDED" -> 3;
                case "FAILED" -> 4;
                default -> throw new IllegalStateException("诊断状态损坏");
            };
            long[] channel = counts.computeIfAbsent(channelId, ignored -> new long[5]);
            channel[0] += count;
            channel[index] += count;
            total[0] += count;
            total[index] += count;
        }
        List<DiagnosisTaskResponse.ChannelProgressResponse> channels = new ArrayList<>();
        for (JsonNode channel : snapshot.path("channels")) {
            long id = channel.path("id").asLong();
            channels.add(new DiagnosisTaskResponse.ChannelProgressResponse(id, channel.path("channelName").asText(),
                    progress(counts.getOrDefault(id, new long[5]))));
        }
        return new DiagnosisTaskResponse(task.getId(), task.getPlanId(), snapshot.path("planName").asText(),
                task.getProjectId(), snapshot.path("project").path("name").asText(), task.getStatus(), progress(total),
                channels, detail ? snapshot : null, task.getCreatedAt(), task.getStartedAt(), task.getFinishedAt());
    }

    /**
     * 显式转换结果，JSON 空值保留为空，不伪造引用或用量。
     * @param row 采样实体或摘要查询结果
     * @return 安全结果响应
     */
    private DiagnosisTaskResponse.ResultResponse resultResponse(GeoDiagnosisPlanExecRecordResultEntity row) {
        JsonNode question = snapshots.read(row.getQuestionSnapshot());
        JsonNode channel = snapshots.read(row.getChannelSnapshot());
        return new DiagnosisTaskResponse.ResultResponse(row.getId(), row.getExecRecordId(), row.getQuestionSortOrder(),
                question.path("text").asText(), question, row.getChannelId(), channel.path("channelName").asText(), channel,
                row.getRepeatIndex(), row.getStatus(), row.getAnswerText(),
                row.getCitations() == null ? null : snapshots.read(row.getCitations()), row.getModelName(),
                row.getUsageInfo() == null ? null : snapshots.read(row.getUsageInfo()), row.getDurationMs(),
                row.getFinishReason(), row.getRawResponse(), row.getErrorMessage(), row.getStartedAt(), row.getFinishedAt());
    }

    /**
     * 状态统计转换成具名字段。
     * @param counts 总数及四种状态数量
     * @return 进度响应
     */
    private DiagnosisTaskResponse.ProgressResponse progress(long[] counts) {
        return new DiagnosisTaskResponse.ProgressResponse(counts[0], counts[1], counts[2], counts[3], counts[4]);
    }

    /**
     * 分页输入校验，SQL 拼接只接受经过校验的数字。
     * @param page 从零开始的页码
     * @param size 每页数量
     */
    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("页码不能为负，每页数量必须为 1 到 100");
        }
    }

    /**
     * 任务与采样状态分别校验，不接受未知筛选条件。
     * @param status 可选状态
     * @param task 是否任务状态
     */
    private void validateStatus(String status, boolean task) {
        if (status == null || status.isBlank()) {
            return;
        }
        Set<String> allowed = task ? Set.of("PENDING", "RUNNING", "SUCCEEDED", "PARTIAL_FAILED", "FAILED")
                : Set.of("PENDING", "RUNNING", "SUCCEEDED", "FAILED");
        if (!allowed.contains(status)) {
            throw new IllegalArgumentException("诊断状态筛选无效");
        }
    }
}
