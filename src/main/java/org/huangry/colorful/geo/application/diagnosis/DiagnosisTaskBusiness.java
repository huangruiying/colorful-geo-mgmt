package org.huangry.colorful.geo.application.diagnosis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskResultStatus;
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
    /**
     * 重新排队当前任务的非成功结果并回读实时进度。
     * @param id 当前任务 ID
     * @return 同一个任务的详情，后台可能已经领取执行
     */
    public DiagnosisTaskResponse restart(long id) {
        service.restart(id);
        return detail(id);
    }

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
     * 按状态和渠道分页查询诊断结果，正文与原始响应不进入列表查询。
     * @param taskId 任务 ID
     * @param status 可选诊断结果状态
     * @param channelId 可选渠道
     * @param page 页码
     * @param size 每页数量
     * @return 诊断结果摘要页
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
     * @param resultId 诊断结果 ID
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
        // 1. 回读冻结配置并汇总诊断结果状态，未知状态明确失败
        JSONObject snapshot = (JSONObject) snapshots.read(task.getPlanSnapshot());
        Map<Long, long[]> counts = new LinkedHashMap<>();
        long[] total = new long[5];
        for (Map<String, Object> row : resultDao.countStatuses(task.getId())) {
            long channelId = ((Number) row.get("channel_id")).longValue();
            long count = ((Number) row.get("sample_count")).longValue();
            String status = (String) row.get("status");
            int index;
            try {
                index = switch (DiagnosisTaskResultStatus.valueOf(status)) {
                    case PENDING -> 1;
                    case RUNNING -> 2;
                    case SUCCEEDED -> 3;
                    case FAILED -> 4;
                };
            } catch (IllegalArgumentException exception) {
                // 数据库未知状态是存储异常，不能按请求筛选无效处理或忽略计数
                throw new IllegalStateException("诊断状态损坏", exception);
            }
            long[] channel = counts.computeIfAbsent(channelId, ignored -> new long[5]);
            channel[0] += count;
            channel[index] += count;
            total[0] += count;
            total[index] += count;
        }
        // 2. 按冻结渠道组装进度，不以当前配置覆盖历史名称
        List<DiagnosisTaskResponse.ChannelProgressResponse> channels = new ArrayList<>();
        for (Object value : snapshot.getJSONArray("channels")) {
            JSONObject channel = (JSONObject) value;
            long id = channel.getLongValue("id");
            channels.add(new DiagnosisTaskResponse.ChannelProgressResponse(id, channel.getString("channelName"),
                    progress(counts.getOrDefault(id, new long[5]))));
        }
        // 3. 详情附带完整快照，列表仅返回摘要
        return new DiagnosisTaskResponse(task.getId(), task.getPlanId(), snapshot.getString("planName"),
                task.getProjectId(), snapshot.getJSONObject("project").getString("name"), task.getStatus(), progress(total),
                channels, detail ? snapshot : null, task.getCreatedAt(), task.getStartedAt(), task.getFinishedAt());
    }

    /**
     * 显式转换结果，JSON 空值保留为空，不伪造引用或用量。
     * @param row 诊断结果实体或摘要查询结果
     * @return 安全结果响应
     */
    private DiagnosisTaskResponse.ResultResponse resultResponse(GeoDiagnosisPlanExecRecordResultEntity row) {
        // 回读问题和渠道对象，保留本次诊断创建时的内容
        JSONObject question = (JSONObject) snapshots.read(row.getQuestionSnapshot());
        JSONObject channel = (JSONObject) snapshots.read(row.getChannelSnapshot());
        // 引用和用量保留原 JSON 结构，缺失时不构造默认数据
        return new DiagnosisTaskResponse.ResultResponse(row.getId(), row.getExecRecordId(), row.getQuestionSortOrder(),
                question.getString("text"), question, row.getChannelId(), channel.getString("channelName"), channel,
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
     * 任务与诊断结果状态分别校验，不接受未知筛选条件。
     * @param status 可选状态
     * @param task 是否任务状态
     */
    private void validateStatus(String status, boolean task) {
        // 未填写不限制状态，保持列表原有的可选筛选行为
        if (status == null || status.isBlank()) {
            return;
        }
        // 按对应枚举校验，整轮的部分失败不能作为单条诊断结果状态
        boolean valid = task
                ? Arrays.stream(DiagnosisTaskStatus.values()).anyMatch(value -> value.name().equals(status))
                : Arrays.stream(DiagnosisTaskResultStatus.values()).anyMatch(value -> value.name().equals(status));
        if (!valid) {
            throw new IllegalArgumentException("诊断状态筛选无效");
        }
    }
}
