package org.huangry.colorful.geo.domain.service.diagnosis;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.alibaba.fastjson.JSONObject;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskResultAnalysisStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskReportStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskResultStatus;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskStatus;
import org.huangry.colorful.geo.infrastructure.repository.ContentPublishRecordDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskResultAnalysisDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskResultDao;
import org.huangry.colorful.geo.infrastructure.repository.GeoDiagnosisTaskReportDao;
import org.huangry.colorful.geo.infrastructure.repository.entity.ContentPublishRecordEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskReportEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 诊断报告持久化与状态流转；每次手动生成创建一份新报告，旧报告保留。
 * <p>状态：PENDING → RUNNING → SUCCEEDED / FAILED；单条分析：PENDING → SUCCEEDED / FAILED。
 * 只分析成功且未因输出上限截断的诊断结果；判定与汇总由后台执行器编排，本服务事务只覆盖数据库操作，不包含模型调用。</p>
 */
@Service
@Slf4j
public class GeoDiagnosisTaskReportService {
    /** 输出达到上限的结束原因；这类回答不完整，不纳入分析。 */
    private static final String TRUNCATED_FINISH_REASON = "length";

    @Resource
    private GeoDiagnosisTaskReportDao reportDao;
    @Resource
    private GeoDiagnosisTaskResultAnalysisDao analysisDao;
    @Resource
    private GeoDiagnosisTaskDao taskDao;
    @Resource
    private GeoDiagnosisTaskResultDao resultDao;
    @Resource
    private ContentPublishRecordDao publishRecordDao;
    @Resource
    private DiagnosisTaskSnapshots snapshots;

    /**
     * 为已结束的诊断任务创建一份待生成的报告，并为每条有效回答建立待判定记录。
     * @param taskId 诊断任务 ID
     * @return 回读后的新报告，状态为 PENDING
     * @throws NoSuchElementException 诊断任务不存在
     * @throws IllegalArgumentException 任务未结束或全部失败、已有报告正在生成、没有可分析的有效回答
     */
    @Transactional
    public GeoDiagnosisTaskReportEntity createReport(long taskId) {
        // 1. 锁住诊断任务，与重新发起、后台领取及并发生成串行；只有结束且有成功结果的任务才能分析
        GeoDiagnosisTaskEntity task = taskDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisTaskEntity>()
                .eq(GeoDiagnosisTaskEntity::getId, taskId).last("FOR UPDATE"));
        if (task == null) {
            throw new NoSuchElementException("诊断任务不存在");
        }
        if (!List.of(DiagnosisTaskStatus.SUCCEEDED.name(), DiagnosisTaskStatus.PARTIAL_FAILED.name()).contains(task.getStatus())) {
            throw new IllegalArgumentException("诊断任务尚未结束或全部失败，无法生成报告");
        }
        // 2. 同一任务同时只允许一份报告在生成，避免重复判定计费
        boolean generating = reportDao.exists(new LambdaQueryWrapper<GeoDiagnosisTaskReportEntity>()
                .eq(GeoDiagnosisTaskReportEntity::getTaskId, taskId)
                .in(GeoDiagnosisTaskReportEntity::getStatus, DiagnosisTaskReportStatus.PENDING.name(), DiagnosisTaskReportStatus.RUNNING.name()));
        if (generating) {
            throw new IllegalArgumentException("该诊断任务已有报告正在生成，请等待完成");
        }
        // 3. 创建报告，并按口径为成功且未截断的回答建立待判定记录；没有有效回答时整体回滚
        GeoDiagnosisTaskReportEntity report = new GeoDiagnosisTaskReportEntity();
        report.setTaskId(taskId);
        report.setStatus(DiagnosisTaskReportStatus.PENDING.name());
        reportDao.insert(report);
        int analysisCount = analysisDao.insertPendingFromResults(report.getId(), taskId,
                DiagnosisTaskResultStatus.SUCCEEDED.name(), TRUNCATED_FINISH_REASON, DiagnosisTaskResultAnalysisStatus.PENDING.name());
        if (analysisCount == 0) {
            throw new IllegalArgumentException("该诊断任务没有可分析的有效回答");
        }
        log.info("诊断报告已创建 reportId={}, taskId={}, analysisCount={}", report.getId(), taskId, analysisCount);
        // 4. 回读数据库生成的时间字段
        return reportDao.selectById(report.getId());
    }

    /**
     * 领取一份待生成的报告；优先继续上次中断的 RUNNING 报告，其次领取最早的 PENDING 报告。
     * <p>单实例执行由后台执行器的数据库会话锁保证，因此残留的 RUNNING 报告可以直接续跑；已判定的回答不会重复判定。</p>
     * @return 已处于 RUNNING 的报告，没有可处理的报告时为 null
     */
    @Transactional
    public GeoDiagnosisTaskReportEntity claimNextReport() {
        // 1. 中断后续跑：剩余待判定记录保持 PENDING，继续处理即可
        GeoDiagnosisTaskReportEntity running = reportDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisTaskReportEntity>()
                .eq(GeoDiagnosisTaskReportEntity::getStatus, DiagnosisTaskReportStatus.RUNNING.name())
                .orderByAsc(GeoDiagnosisTaskReportEntity::getId).last("LIMIT 1 FOR UPDATE"));
        if (running != null) {
            return running;
        }
        // 2. 领取最早排队的报告，状态更新成功后才开始调用模型
        GeoDiagnosisTaskReportEntity report = reportDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisTaskReportEntity>()
                .eq(GeoDiagnosisTaskReportEntity::getStatus, DiagnosisTaskReportStatus.PENDING.name())
                .orderByAsc(GeoDiagnosisTaskReportEntity::getId).last("LIMIT 1 FOR UPDATE"));
        if (report == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        int changed = reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisTaskReportEntity>()
                .eq(GeoDiagnosisTaskReportEntity::getId, report.getId())
                .eq(GeoDiagnosisTaskReportEntity::getStatus, DiagnosisTaskReportStatus.PENDING.name())
                .set(GeoDiagnosisTaskReportEntity::getStatus, DiagnosisTaskReportStatus.RUNNING.name())
                .set(GeoDiagnosisTaskReportEntity::getStartedAt, now));
        if (changed != 1) {
            return null;
        }
        report.setStatus(DiagnosisTaskReportStatus.RUNNING.name());
        report.setStartedAt(now);
        return report;
    }

    /**
     * 冻结本报告的判定渠道与提示词版本；已冻结的报告不改写，保证续跑时使用同一判定口径。
     * @param reportId 报告 ID
     * @param judgeChannelSnapshot 判定渠道的非敏感配置快照 JSON
     * @param promptVersion 判定提示词版本
     */
    @Transactional
    public void freezeJudgeChannel(long reportId, String judgeChannelSnapshot, String promptVersion) {
        reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisTaskReportEntity>()
                .eq(GeoDiagnosisTaskReportEntity::getId, reportId)
                .isNull(GeoDiagnosisTaskReportEntity::getJudgeChannelSnapshot)
                .set(GeoDiagnosisTaskReportEntity::getJudgeChannelSnapshot, judgeChannelSnapshot)
                .set(GeoDiagnosisTaskReportEntity::getPromptVersion, promptVersion));
    }

    /**
     * 按 ID 顺序取下一条待判定记录。
     * @param reportId 报告 ID
     * @return 待判定记录，没有剩余时为 null
     */
    public GeoDiagnosisTaskResultAnalysisEntity findNextPendingAnalysis(long reportId) {
        return analysisDao.selectOne(new LambdaQueryWrapper<GeoDiagnosisTaskResultAnalysisEntity>()
                .eq(GeoDiagnosisTaskResultAnalysisEntity::getReportId, reportId)
                .eq(GeoDiagnosisTaskResultAnalysisEntity::getStatus, DiagnosisTaskResultAnalysisStatus.PENDING.name())
                .orderByAsc(GeoDiagnosisTaskResultAnalysisEntity::getId).last("LIMIT 1"));
    }

    /**
     * 保存一条判定成功的分析结果；只允许待判定记录进入成功，避免覆盖已有结果。
     * @param analysis 填好品牌情感、描述准确性及 brands、points、sources JSON 的分析记录
     * @throws IllegalStateException 记录不处于待判定状态，未保存
     */
    @Transactional
    public void updateAnalysisSucceeded(GeoDiagnosisTaskResultAnalysisEntity analysis) {
        int changed = analysisDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisTaskResultAnalysisEntity>()
                .eq(GeoDiagnosisTaskResultAnalysisEntity::getId, analysis.getId())
                .eq(GeoDiagnosisTaskResultAnalysisEntity::getStatus, DiagnosisTaskResultAnalysisStatus.PENDING.name())
                .set(GeoDiagnosisTaskResultAnalysisEntity::getStatus, DiagnosisTaskResultAnalysisStatus.SUCCEEDED.name())
                .set(GeoDiagnosisTaskResultAnalysisEntity::getBrandSentiment, analysis.getBrandSentiment())
                .set(GeoDiagnosisTaskResultAnalysisEntity::getDescriptionAccurate, analysis.getDescriptionAccurate())
                .set(GeoDiagnosisTaskResultAnalysisEntity::getBrands, analysis.getBrands())
                .set(GeoDiagnosisTaskResultAnalysisEntity::getPoints, analysis.getPoints())
                .set(GeoDiagnosisTaskResultAnalysisEntity::getSources, analysis.getSources()));
        if (changed != 1) {
            throw new IllegalStateException("诊断回答分析结果保存失败");
        }
    }

    /**
     * 记录一条判定失败；失败不重试，该回答不计入依赖判定的指标。
     * @param analysisId 分析记录 ID
     * @param reason 脱敏后的失败原因，不接收远程异常原文
     * @param sources 规则归类的来源 JSON，判定失败仍保留供来源分析使用，可为 null
     * @throws IllegalStateException 记录不处于待判定状态，未保存
     */
    @Transactional
    public void updateAnalysisFailed(long analysisId, String reason, String sources) {
        int changed = analysisDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisTaskResultAnalysisEntity>()
                .eq(GeoDiagnosisTaskResultAnalysisEntity::getId, analysisId)
                .eq(GeoDiagnosisTaskResultAnalysisEntity::getStatus, DiagnosisTaskResultAnalysisStatus.PENDING.name())
                .set(GeoDiagnosisTaskResultAnalysisEntity::getStatus, DiagnosisTaskResultAnalysisStatus.FAILED.name())
                .set(GeoDiagnosisTaskResultAnalysisEntity::getErrorMessage, reason)
                .set(GeoDiagnosisTaskResultAnalysisEntity::getSources, sources));
        if (changed != 1) {
            throw new IllegalStateException("诊断回答分析失败结果保存失败");
        }
    }

    /**
     * 冻结汇总并结束报告；只更新 RUNNING 报告。
     * @param reportId 报告 ID
     * @param summary 冻结的汇总 JSON
     */
    @Transactional
    public void finishReport(long reportId, String summary) {
        reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisTaskReportEntity>()
                .eq(GeoDiagnosisTaskReportEntity::getId, reportId)
                .eq(GeoDiagnosisTaskReportEntity::getStatus, DiagnosisTaskReportStatus.RUNNING.name())
                .set(GeoDiagnosisTaskReportEntity::getStatus, DiagnosisTaskReportStatus.SUCCEEDED.name())
                .set(GeoDiagnosisTaskReportEntity::getSummary, summary)
                .set(GeoDiagnosisTaskReportEntity::getFinishedAt, LocalDateTime.now()));
    }

    /**
     * 将报告标记为失败；只更新 RUNNING 报告，已保存的分析结果保留。
     * @param reportId 报告 ID
     * @param reason 脱敏后的失败原因
     */
    @Transactional
    public void failReport(long reportId, String reason) {
        reportDao.update(null, new LambdaUpdateWrapper<GeoDiagnosisTaskReportEntity>()
                .eq(GeoDiagnosisTaskReportEntity::getId, reportId)
                .eq(GeoDiagnosisTaskReportEntity::getStatus, DiagnosisTaskReportStatus.RUNNING.name())
                .set(GeoDiagnosisTaskReportEntity::getStatus, DiagnosisTaskReportStatus.FAILED.name())
                .set(GeoDiagnosisTaskReportEntity::getErrorMessage, reason)
                .set(GeoDiagnosisTaskReportEntity::getFinishedAt, LocalDateTime.now()));
    }

    /**
     * 读取被分析的诊断结果。
     * @param resultId 诊断结果 ID
     * @return 诊断结果
     * @throws NoSuchElementException 诊断结果不存在
     */
    public GeoDiagnosisTaskResultEntity getTaskResult(long resultId) {
        GeoDiagnosisTaskResultEntity result = resultDao.selectById(resultId);
        if (result == null) {
            throw new NoSuchElementException("诊断结果不存在");
        }
        return result;
    }

    /**
     * 读取报告所属诊断任务的冻结快照，提供项目资料、问题与渠道配置。
     * @param taskId 诊断任务 ID
     * @return 任务快照 JSON
     * @throws NoSuchElementException 诊断任务不存在
     */
    public JSONObject getTaskSnapshot(long taskId) {
        GeoDiagnosisTaskEntity task = taskDao.selectById(taskId);
        if (task == null) {
            throw new NoSuchElementException("诊断任务不存在");
        }
        return (JSONObject) snapshots.parseSnapshot(task.getPlanSnapshot());
    }

    /**
     * 读取我方已公开发布内容的链接，用于判断来源是否为我方发布。
     * @return 去除首尾空白的公开链接集合
     */
    public Set<String> listPublishedUrls() {
        Set<String> urls = new HashSet<>();
        for (ContentPublishRecordEntity record : publishRecordDao.selectList(new LambdaQueryWrapper<ContentPublishRecordEntity>()
                .isNotNull(ContentPublishRecordEntity::getPublishedUrl).select(ContentPublishRecordEntity::getPublishedUrl))) {
            if (record.getPublishedUrl() != null && !record.getPublishedUrl().isBlank()) {
                urls.add(record.getPublishedUrl().strip());
            }
        }
        return urls;
    }

    /**
     * 读取报告的全部分析记录及对应诊断结果，供汇总使用。
     * @param reportId 报告 ID
     * @return 按分析 ID 顺序的回答列表
     */
    public List<ReportAnswer> loadAnswers(long reportId) {
        // 1. 读取本报告全部分析记录
        List<GeoDiagnosisTaskResultAnalysisEntity> analyses = analysisDao.selectList(new LambdaQueryWrapper<GeoDiagnosisTaskResultAnalysisEntity>()
                .eq(GeoDiagnosisTaskResultAnalysisEntity::getReportId, reportId)
                .orderByAsc(GeoDiagnosisTaskResultAnalysisEntity::getId));
        if (analyses.isEmpty()) {
            return List.of();
        }
        // 2. 一次查询对应诊断结果，按 ID 组装，避免逐条往返
        Map<Long, GeoDiagnosisTaskResultEntity> results = resultDao.selectBatchIds(
                analyses.stream().map(GeoDiagnosisTaskResultAnalysisEntity::getTaskResultId).toList()).stream()
                .collect(Collectors.toMap(GeoDiagnosisTaskResultEntity::getId, Function.identity()));
        return analyses.stream().map(analysis -> new ReportAnswer(analysis, results.get(analysis.getTaskResultId()))).toList();
    }

    /**
     * 汇总用的一条回答：分析记录与对应诊断结果。
     * @param analysis 分析记录
     * @param result 诊断结果，包含问题与渠道快照、回答、引用与原始响应
     */
    public record ReportAnswer(GeoDiagnosisTaskResultAnalysisEntity analysis, GeoDiagnosisTaskResultEntity result) {
    }
}
