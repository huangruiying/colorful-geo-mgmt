package org.huangry.colorful.geo.domain.service.diagnosis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import jakarta.annotation.Resource;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskResultStatus;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.application.diagnosis.model.CreateDiagnosisTaskRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMStrategyRouter;
import org.huangry.colorful.geo.infrastructure.repository.*;
import org.huangry.colorful.geo.infrastructure.repository.entity.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** 诊断任务持久化与状态流转；事务只覆盖数据库操作，不包含外部模型调用。 */
@Service
@Slf4j
public class GeoDiagnosisTaskService {
    @Resource
    private GeoDiagnosisPlanExecRecordDao taskDao;
    @Resource
    private GeoDiagnosisPlanExecRecordResultDao resultDao;
    @Resource
    private GeoDiagnosisPlanDao planDao;
    @Resource
    private GeoProjectInfoDao projectDao;
    @Resource
    private GeoLlmChannelDao channelDao;
    @Resource
    private GeoDiagnosisPlanService planService;
    @Resource
    private LLMStrategyRouter router;
    @Resource
    private DiagnosisTaskSnapshots snapshots;

    /**
     * 在同一事务冻结方案和全部诊断结果，失败时不留下半个任务。
     * @param request 方案及本次创建标识
     * @return 新任务或相同提交已创建的任务
     */
    @Transactional
    public GeoDiagnosisPlanExecRecordEntity create(CreateDiagnosisTaskRequest request) {
        // 1. 校验提交并锁定方案，和方案编辑共用主行锁，避免快照集合混杂。
        if (request == null || request.planId() == null || request.planId() <= 0
                || request.requestId() == null || !request.requestId().matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("请选择方案，并提供 1 到 64 位字母、数字、短横线或下划线组成的提交标识");
        }
        GeoDiagnosisPlanEntity plan = planDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisPlanEntity>()
                .eq(GeoDiagnosisPlanEntity::getId, request.planId()).last("FOR UPDATE"));
        if (plan == null) {
            throw new NoSuchElementException("诊断方案不存在");
        }
        GeoDiagnosisPlanExecRecordEntity existing = findByRequest(request.requestId());
        if (existing != null) {
            requireSamePlan(existing, request.planId());
            return existing;
        }
        GeoProjectInfoEntity project = projectDao.selectOne(new LambdaQueryWrapper<GeoProjectInfoEntity>()
                .eq(GeoProjectInfoEntity::getId, plan.getProjectId()).last("FOR SHARE"));
        if (project == null) {
            throw new IllegalArgumentException("方案关联的项目不存在");
        }
        List<GeoDiagnosisPlanQuestionEntity> questions = planService.listQuestions(plan.getId());
        planService.validateQuestions(project, questions);
        if (plan.getRepeatCount() == null || plan.getRepeatCount() < 1 || plan.getRepeatCount() > 10) {
            throw new IllegalArgumentException("方案重复执行次数必须为 1 到 10");
        }
        List<GeoDiagnosisPlanChannelEntity> selected = planService.listChannels(plan.getId());
        if (selected.isEmpty() || selected.size() > 20) {
            throw new IllegalArgumentException("方案必须选择 1 到 20 个测试渠道");
        }
        List<GeoLlmChannelEntity> channels = new ArrayList<>();
        for (GeoDiagnosisPlanChannelEntity selectedChannel : selected) {
            GeoLlmChannelEntity channel = channelDao.selectOne(new LambdaQueryWrapper<GeoLlmChannelEntity>()
                    .eq(GeoLlmChannelEntity::getId, selectedChannel.getChannelId()).last("FOR SHARE"));
            channels.add(channel);
        }
        // 2. 只保存字段白名单快照，密钥保留在渠道表，不随任务复制。
        GeoDiagnosisPlanExecRecordEntity task = new GeoDiagnosisPlanExecRecordEntity();
        task.setRequestId(request.requestId());
        task.setPlanId(plan.getId());
        task.setProjectId(project.getId());
        task.setPlanSnapshot(JSON.toJSONString(snapshots.plan(plan, project, questions, channels), SerializerFeature.WriteMapNullValue));
        task.setStatus(DiagnosisTaskStatus.PENDING.name());
        if (taskDao.insert(task) != 1) {
            throw new IllegalStateException("诊断任务创建失败");
        }
        // 3. 每 250 条批量落库；唯一键固定本轮问题、渠道和重复序号。
        insertSamples(task.getId(), questions, channels, plan.getRepeatCount());
        return taskDao.selectById(task.getId());
    }

    /**
     * 初始化每次诊断的结果记录，避免重复序列化渠道配置与单条数据库往返。
     * @param taskId 所属任务
     * @param questions 问题集合
     * @param channels 渠道集合
     * @param repeatCount 每题每渠道次数
     */
    private void insertSamples(long taskId, List<GeoDiagnosisPlanQuestionEntity> questions,
            List<GeoLlmChannelEntity> channels, int repeatCount) {
        // 1. 每个渠道只序列化一次，显式保留空值，所有诊断结果复用同一冻结配置
        Map<Long, String> channelJson = new HashMap<>();
        channels.forEach(channel -> channelJson.put(channel.getId(), JSON.toJSONString(snapshots.channel(channel), SerializerFeature.WriteMapNullValue)));
        List<GeoDiagnosisPlanExecRecordResultEntity> batch = new ArrayList<>(250);
        // 2. 按问题、渠道、重复序号组装独立诊断，问题快照不附带项目背景
        for (GeoDiagnosisPlanQuestionEntity question : questions) {
            String questionJson = JSON.toJSONString(snapshots.question(question), SerializerFeature.WriteMapNullValue);
            for (GeoLlmChannelEntity channel : channels) {
                for (int repeat = 1; repeat <= repeatCount; repeat++) {
                    GeoDiagnosisPlanExecRecordResultEntity sample = new GeoDiagnosisPlanExecRecordResultEntity();
                    sample.setExecRecordId(taskId);
                    sample.setQuestionId(question.getId());
                    sample.setQuestionSortOrder(question.getSortOrder());
                    sample.setQuestionSnapshot(questionJson);
                    sample.setChannelId(channel.getId());
                    sample.setChannelSnapshot(channelJson.get(channel.getId()));
                    sample.setRepeatIndex(repeat);
                    sample.setStatus(DiagnosisTaskResultStatus.PENDING.name());
                    batch.add(sample);
                    // 3. 满批即写入创建事务，唯一键固定单次诊断标识，异常由外层事务回滚
                    if (batch.size() == 250) {
                        persistBatch(batch);
                        batch.clear();
                    }
                }
            }
        }
        // 收尾写入不足一批的剩余待执行诊断，避免丢失最后一组结果
        if (!batch.isEmpty()) {
            persistBatch(batch);
        }
    }

    /**
     * 插入数量异常同样回滚创建事务。
     * @param batch 一批诊断结果
     */
    private void persistBatch(List<GeoDiagnosisPlanExecRecordResultEntity> batch) {
        if (resultDao.insertSamples(batch) != batch.size()) {
            throw new IllegalStateException("诊断结果初始化失败");
        }
    }

    /**
     * 回读提交标识，供事务外处理并发唯一键冲突。
     * @param requestId 本次提交标识
     * @return 已创建任务或空
     */
    public GeoDiagnosisPlanExecRecordEntity findByRequest(String requestId) {
        return taskDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisPlanExecRecordEntity>()
                .eq(GeoDiagnosisPlanExecRecordEntity::getRequestId, requestId).last("LIMIT 1"));
    }

    /**
     * 同一提交不能用于另一个方案。
     * @param task 已创建任务
     * @param planId 本次方案
     */
    public void requireSamePlan(GeoDiagnosisPlanExecRecordEntity task, Long planId) {
        if (!Objects.equals(task.getPlanId(), planId)) {
            throw new IllegalArgumentException("提交标识已用于其他方案，请重新发起诊断");
        }
    }

    /**
     * 重新排队已结束任务的非成功结果，保留成功回答及原冻结配置。
     * @param taskId 当前任务 ID
     */
    @Transactional
    public void restart(long taskId) {
        // 1. 锁住主任务，与后台领取和并发重新发起串行；活动任务不能重置。
        var task = taskDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisPlanExecRecordEntity>()
                .eq(GeoDiagnosisPlanExecRecordEntity::getId, taskId).last("FOR UPDATE"));
        if (task == null) {
            throw new NoSuchElementException("诊断任务不存在");
        }
        if (!DiagnosisTaskStatus.FAILED.name().equals(task.getStatus())
                && !DiagnosisTaskStatus.PARTIAL_FAILED.name().equals(task.getStatus())) {
            throw new IllegalArgumentException("仅失败或部分失败的已结束任务可以重新发起");
        }
        // 2. 只清空非成功结果的旧调用数据；问题、渠道、重复序号和成功结果不变。
        int changed = resultDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisPlanExecRecordResultEntity>()
                .eq(GeoDiagnosisPlanExecRecordResultEntity::getExecRecordId, taskId)
                .ne(GeoDiagnosisPlanExecRecordResultEntity::getStatus, DiagnosisTaskResultStatus.SUCCEEDED.name())
                .set(GeoDiagnosisPlanExecRecordResultEntity::getStatus, DiagnosisTaskResultStatus.PENDING.name())
                .set(GeoDiagnosisPlanExecRecordResultEntity::getAnswerText, null)
                .set(GeoDiagnosisPlanExecRecordResultEntity::getCitations, null)
                .set(GeoDiagnosisPlanExecRecordResultEntity::getModelName, null)
                .set(GeoDiagnosisPlanExecRecordResultEntity::getUsageInfo, null)
                .set(GeoDiagnosisPlanExecRecordResultEntity::getDurationMs, null)
                .set(GeoDiagnosisPlanExecRecordResultEntity::getFinishReason, null)
                .set(GeoDiagnosisPlanExecRecordResultEntity::getRawResponse, null)
                .set(GeoDiagnosisPlanExecRecordResultEntity::getErrorMessage, null)
                .set(GeoDiagnosisPlanExecRecordResultEntity::getStartedAt, null)
                .set(GeoDiagnosisPlanExecRecordResultEntity::getFinishedAt, null));
        if (changed == 0) {
            throw new IllegalArgumentException("当前任务没有需要重新执行的诊断结果");
        }
        // 3. 主从记录同一事务提交后才对后台可见，不在接口中直接调用模型。
        if (taskDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisPlanExecRecordEntity>()
                .eq(GeoDiagnosisPlanExecRecordEntity::getId, taskId)
                .eq(GeoDiagnosisPlanExecRecordEntity::getStatus, task.getStatus())
                .set(GeoDiagnosisPlanExecRecordEntity::getStatus, DiagnosisTaskStatus.PENDING.name())
                .set(GeoDiagnosisPlanExecRecordEntity::getStartedAt, null)
                .set(GeoDiagnosisPlanExecRecordEntity::getFinishedAt, null)) != 1) {
            throw new IllegalStateException("诊断任务重新排队失败");
        }
        log.info("诊断任务重新排队 taskId={}, resultCount={}", taskId, changed);
    }

    /**
     * 领取最早排队的任务，状态更新提交后才开始外部调用。
     * @return 本次领取的任务，没有排队任务时为空
     */
    @Transactional
    public GeoDiagnosisPlanExecRecordEntity claimNext() {
        // 锁定最早排队的任务，终态和执行中的任务不重复领取
        GeoDiagnosisPlanExecRecordEntity task = taskDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisPlanExecRecordEntity>()
                .eq(GeoDiagnosisPlanExecRecordEntity::getStatus, DiagnosisTaskStatus.PENDING.name())
                .orderByAsc(GeoDiagnosisPlanExecRecordEntity::getId).last("LIMIT 1 FOR UPDATE"));
        if (task == null) {
            return null;
        }
        // 仅把仍待执行的任务置为执行中，更新成功后才交给后台调用模型
        int changed = taskDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisPlanExecRecordEntity>()
                .eq(GeoDiagnosisPlanExecRecordEntity::getId, task.getId()).eq(GeoDiagnosisPlanExecRecordEntity::getStatus, DiagnosisTaskStatus.PENDING.name())
                .set(GeoDiagnosisPlanExecRecordEntity::getStatus, DiagnosisTaskStatus.RUNNING.name())
                .set(GeoDiagnosisPlanExecRecordEntity::getStartedAt, LocalDateTime.now()));
        return changed == 1 ? task : null;
    }

    /**
     * 领取一次待执行的诊断，不在事务中等待模型响应。
     * @param taskId 正在执行的任务
     * @return 诊断结果或空
     */
    @Transactional
    public GeoDiagnosisPlanExecRecordResultEntity claimSample(long taskId) {
        // 1. 按冻结的诊断结果顺序锁定一条待执行记录，没有剩余待执行诊断时结束领取
        GeoDiagnosisPlanExecRecordResultEntity result = resultDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisPlanExecRecordResultEntity>()
                .eq(GeoDiagnosisPlanExecRecordResultEntity::getExecRecordId, taskId)
                .eq(GeoDiagnosisPlanExecRecordResultEntity::getStatus, DiagnosisTaskResultStatus.PENDING.name())
                .orderByAsc(GeoDiagnosisPlanExecRecordResultEntity::getQuestionSortOrder)
                .orderByAsc(GeoDiagnosisPlanExecRecordResultEntity::getChannelId)
                .orderByAsc(GeoDiagnosisPlanExecRecordResultEntity::getRepeatIndex).last("LIMIT 1 FOR UPDATE"));
        if (result == null) {
            return null;
        }
        // 2. 记录本次开始时间并进入执行中，区别于整轮任务状态
        result.setStartedAt(LocalDateTime.now());
        result.setStatus(DiagnosisTaskResultStatus.RUNNING.name());
        // 3. 领取状态必须成功落库，失败时不得继续调用模型
        if (resultDao.updateById(result) != 1) {
            throw new IllegalStateException("单次诊断领取失败");
        }
        return result;
    }

    /**
     * 精确读取原渠道及当前凭据，配置变化时拒绝静默替换测试条件。
     * @param sample 冻结的诊断结果
     * @return 本次调用的渠道实体，仅在服务端使用
     */
    public GeoLlmChannelEntity prepareChannel(GeoDiagnosisPlanExecRecordResultEntity sample) {
        // 读取原渠道，再以同一解析规则还原双方配置用于比较
        GeoLlmChannelEntity channel = channelDao.selectById(sample.getChannelId());
        // 双方统一解析整数与小数，保留显式空值，避免更换 JSON 库后误判历史配置变化。
        var current = (JSONObject) snapshots.read(JSON.toJSONString(snapshots.channel(channel), SerializerFeature.WriteMapNullValue));
        var frozen = (JSONObject) snapshots.read(sample.getChannelSnapshot());
        // 配置不同则拒绝执行，只输出字段名；密钥不在快照中，轮换不影响比较
        if (!current.equals(frozen)) {
            List<String> changed = new ArrayList<>();
            current.keySet().forEach(name -> {
                if (!Objects.equals(current.get(name), frozen.get(name))) {
                    changed.add(name);
                }
            });
            // 只记录发生变化的字段名，不输出配置内容、地址或凭据。
            log.warn("诊断渠道配置变化 taskId={}, resultId={}, channelId={}, fields={}",
                    sample.getExecRecordId(), sample.getId(), sample.getChannelId(), changed);
            throw new IllegalArgumentException("渠道配置已变化，请重新创建诊断任务");
        }
        return channel;
    }

    /**
     * 在短事务保存一次成功回答；响应未提供的元数据保留为空。
     * @param sample 正在执行的诊断结果
     * @param response 模型正文、引用与厂商原始元数据
     * @param durationMs 调用耗时
     */
    @Transactional
    public void succeed(GeoDiagnosisPlanExecRecordResultEntity sample, LLMResponse response, long durationMs) {
        // 1. 显式映射引用 record，Fastjson 1.x 不识别其访问器，避免序列化为空对象
        var citations = new ArrayList<JSONObject>();
        for (var citation : response.getCitations()) {
            citations.add(new JSONObject(true).fluentPut("title", citation.title()).fluentPut("url", citation.url())
                    .fluentPut("startIndex", citation.startIndex()).fluentPut("endIndex", citation.endIndex())
                    .fluentPut("snippet", citation.snippet()));
        }
        // 2. 组装本次成功结果，引用空值与厂商元数据保持原样
        sample.setStatus(DiagnosisTaskResultStatus.SUCCEEDED.name());
        sample.setAnswerText(response.getContent());
        sample.setCitations(JSON.toJSONString(citations, SerializerFeature.WriteMapNullValue));
        sample.setModelName(response.getModelName());
        sample.setUsageInfo(response.getUsageInfo());
        sample.setFinishReason(response.getFinishReason());
        sample.setRawResponse(response.getRawResponse());
        sample.setDurationMs(durationMs);
        sample.setFinishedAt(LocalDateTime.now());
        // 3. 仅允许正在执行的诊断结果完成；保存失败向外暴露，不能重新调用模型
        if (resultDao.update(sample, new LambdaUpdateWrapper<GeoDiagnosisPlanExecRecordResultEntity>()
                .eq(GeoDiagnosisPlanExecRecordResultEntity::getId, sample.getId())
                .eq(GeoDiagnosisPlanExecRecordResultEntity::getStatus, DiagnosisTaskResultStatus.RUNNING.name())) != 1) {
            throw new IllegalStateException("诊断结果保存失败");
        }
    }

    /**
     * 保存安全失败原因，失败后继续其他诊断，不自动再次调用模型。
     * @param sample 当前诊断结果
     * @param reason 固定分类或本地校验提示，不接收远程异常原文
     * @param durationMs 已消耗时间
     */
    @Transactional
    public void fail(GeoDiagnosisPlanExecRecordResultEntity sample, String reason, long durationMs) {
        // 仅允许执行中的诊断结果进入失败终态，不覆盖已经保存的成功或失败结果
        int changed = resultDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisPlanExecRecordResultEntity>()
                .eq(GeoDiagnosisPlanExecRecordResultEntity::getId, sample.getId())
                .eq(GeoDiagnosisPlanExecRecordResultEntity::getStatus, DiagnosisTaskResultStatus.RUNNING.name())
                .set(GeoDiagnosisPlanExecRecordResultEntity::getStatus, DiagnosisTaskResultStatus.FAILED.name())
                .set(GeoDiagnosisPlanExecRecordResultEntity::getErrorMessage, reason)
                .set(GeoDiagnosisPlanExecRecordResultEntity::getDurationMs, durationMs)
                .set(GeoDiagnosisPlanExecRecordResultEntity::getFinishedAt, LocalDateTime.now()));
        // 写入数量不符表示未成功保存失败结果，向外暴露而非继续伪装执行成功
        if (changed != 1) {
            throw new IllegalStateException("诊断失败结果保存失败");
        }
    }

    /**
     * 所有单次诊断结束后汇总任务终态，不重复维护统计字段。
     * @param taskId 本轮任务
     */
    @Transactional
    public void finish(long taskId) {
        // 1. 汇总诊断结果终态；仍有待执行或执行中记录时拒绝结束整轮任务
        long succeeded = 0;
        long failed = 0;
        for (Map<String, Object> count : resultDao.countStatuses(taskId)) {
            String status = (String) count.get("status");
            long number = ((Number) count.get("sample_count")).longValue();
            if (DiagnosisTaskResultStatus.PENDING.name().equals(status) || DiagnosisTaskResultStatus.RUNNING.name().equals(status)) {
                throw new IllegalStateException("任务仍有未完成的诊断");
            }
            if (DiagnosisTaskResultStatus.SUCCEEDED.name().equals(status)) {
                succeeded += number;
            } else {
                failed += number;
            }
        }
        // 2. 整轮独立判定：无成功为失败、无失败为成功，否则为部分失败
        DiagnosisTaskStatus status = succeeded == 0 ? DiagnosisTaskStatus.FAILED
                : failed == 0 ? DiagnosisTaskStatus.SUCCEEDED : DiagnosisTaskStatus.PARTIAL_FAILED;
        // 3. 仅更新执行中的整轮任务，保留既有终态
        taskDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisPlanExecRecordEntity>()
                .eq(GeoDiagnosisPlanExecRecordEntity::getId, taskId)
                .eq(GeoDiagnosisPlanExecRecordEntity::getStatus, DiagnosisTaskStatus.RUNNING.name())
                .set(GeoDiagnosisPlanExecRecordEntity::getStatus, status.name())
                .set(GeoDiagnosisPlanExecRecordEntity::getFinishedAt, LocalDateTime.now()));
    }

    /**
     * 中断任务只标记未完成的诊断，已保存回答保留且不重复发送请求。
     * @param taskId 被中断的任务
     * @param reason 已脱敏的本地中断原因
     */
    @Transactional
    public void interrupt(long taskId, String reason) {
        // 先终止未完成的诊断，已保存的成功或失败结果保持原样
        resultDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisPlanExecRecordResultEntity>()
                .eq(GeoDiagnosisPlanExecRecordResultEntity::getExecRecordId, taskId)
                .in(GeoDiagnosisPlanExecRecordResultEntity::getStatus, DiagnosisTaskResultStatus.PENDING.name(), DiagnosisTaskResultStatus.RUNNING.name())
                .set(GeoDiagnosisPlanExecRecordResultEntity::getStatus, DiagnosisTaskResultStatus.FAILED.name())
                .set(GeoDiagnosisPlanExecRecordResultEntity::getErrorMessage, reason)
                .set(GeoDiagnosisPlanExecRecordResultEntity::getFinishedAt, LocalDateTime.now()));
        // 再根据所有诊断结果汇总整轮终态，不把整轮状态直接当作单条结果状态
        finish(taskId);
    }

    /**
     * 仅在取得唯一执行锁后调用，恢复此前实例中断的任务。
     */
    @Transactional
    public void recoverInterrupted() {
        // 只恢复此前已领取的任务，待执行与已经结束的任务不在恢复范围内
        for (GeoDiagnosisPlanExecRecordEntity task : taskDao.selectList(new LambdaQueryWrapper<GeoDiagnosisPlanExecRecordEntity>()
                .eq(GeoDiagnosisPlanExecRecordEntity::getStatus, DiagnosisTaskStatus.RUNNING.name()))) {
            // 将状态不确定且未完成的诊断收尾为失败，保留已有回答，避免重复计费
            interrupt(task.getId(), "执行服务中断，未完成的诊断不自动重发；可重新发起整轮诊断");
        }
    }
}
