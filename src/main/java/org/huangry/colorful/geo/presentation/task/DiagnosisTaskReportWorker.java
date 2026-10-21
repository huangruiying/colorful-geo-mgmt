package org.huangry.colorful.geo.presentation.task;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService.ReportAnswer;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskReportService;
import org.huangry.colorful.geo.domain.service.diagnosis.report.analyze.ResultAnalyzer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.analyze.ResultJudgePrompt;
import org.huangry.colorful.geo.domain.service.diagnosis.report.llm.ReportLlmAnalyzer;
import org.huangry.colorful.geo.domain.service.diagnosis.report.summary.ReportSummarizer;
import org.huangry.colorful.geo.domain.service.llm.GeoLlmChannelService;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMStrategyRouter;
import org.huangry.colorful.geo.infrastructure.common.enums.DiagnosisTaskResultAnalysisStatus;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskReportEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisTaskResultAnalysisEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoLlmChannelEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/**
 * 诊断报告离线 Job：领取报告，逐条判定有效回答，最后归一维度、汇总并冻结结果。
 * <p>start 向统一线程池提交单个轮询，tick 领取报告，generate 编排判定与汇总，close 通知退出。
 * 数据库会话锁限定同一库唯一执行者，中断的报告下次领取时从剩余待判定记录续跑；判定失败不重试。</p>
 */
@Component
@Slf4j
public class DiagnosisTaskReportWorker {
	/**
	 * 数据库会话锁名称，与诊断任务执行器的锁区分。
	 */
	private static final String LOCK_NAME = ":diagnosis-report-worker";

	@Resource
	private GeoDiagnosisTaskReportService geoDiagnosisTaskReportService;
	@Resource
	private ResultAnalyzer resultAnalyzer;
	@Resource
	private ReportSummarizer reportSummarizer;
	@Resource
	private ReportLlmAnalyzer reportLlmAnalyzer;
	@Resource
	private DiagnosisTaskSnapshots diagnosisTaskSnapshots;
	@Resource
	private LLMStrategyRouter router;
	@Resource
	private GeoLlmChannelService channelService;
	@Resource
	private DataSource dataSource;
	@Value("${diagnosis.reports.enabled:true}")
	private boolean enabled;
	@Resource(name = "diagnosisTaskExecutor")
	private TaskExecutor taskExecutor;

	/**
	 * 避免重复启动事件提交多个轮询任务。
	 */
	private final AtomicBoolean started = new AtomicBoolean();
	/**
	 * 本组件实际执行线程，仅用于停机通知。
	 */
	private volatile Thread workerThread;
	/**
	 * 持有数据库会话锁的连接，执行线程退出前保持占用。
	 */
	private Connection lease;
	/**
	 * 停机后禁止领取新报告和新的判定。
	 */
	private volatile boolean stopped;
	/**
	 * 基础设施故障后的下次轮询时间，使用单调时钟纳秒值。
	 */
	private long nextAttemptAt;

	/**
	 * 应用启动完成后向统一线程池提交唯一轮询任务。
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (enabled && ! stopped && started.compareAndSet(false, true)) {
			taskExecutor.execute(this::poll);
		}
	}

	/**
	 * 顺序轮询；每轮结束后等待一秒，退出时由执行线程释放数据库锁。
	 */
	private void poll() {
		workerThread = Thread.currentThread();
		try {
			// 1. 首次及每轮结束后间隔一秒，停机或线程中断时退出
			while (! stopped && ! Thread.currentThread().isInterrupted()) {
				TimeUnit.SECONDS.sleep(1);
				if (! stopped) {
					// 2. 顺序处理一份报告，再等待下一次轮询
					tick();
				}
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} finally {
			// 3. 判定调用结束后才释放锁，避免其他实例提前接管同一报告
			releaseLease();
			workerThread = null;
		}
	}

	/**
	 * 隔离一次报告故障；数据库异常后报告保持 RUNNING，下次轮询续跑。
	 */
	private void tick() {
		if (stopped || System.nanoTime() < nextAttemptAt) {
			return;
		}
		Long reportId = null;
		try {
			// 1. 先取得同库执行权，未持锁的实例不调用模型
			if (! acquireLease()) {
				return;
			}
			// 2. 提取待处理的 诊断报告
			GeoDiagnosisTaskReportEntity report = geoDiagnosisTaskReportService.extrateUnprocessReport();
			if (report != null) {
				reportId = report.getId();
				// 进行报告生成
				generate(report);
			}
		} catch (RuntimeException | SQLException exception) {
			// 基础设施故障等待五秒再轮询；已判定的回答不重复判定，异常日志不带远端原文
			log.error("诊断报告生成中断 reportId={}, errorType={}", reportId, exception.getClass().getSimpleName());
			nextAttemptAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		}
	}

	/**
	 * 生成一份报告：冻结判定渠道 → 逐条判定 → 归一维度并汇总。
	 * <p>报告由 createReport 建好、状态为待处理，后台捞到后从这里开始判定和汇总。</p>
	 *
	 * @param report 已处于 RUNNING 的报告
	 * @throws SQLException 执行锁连接检查失败，交由轮询处理
	 */
	private void generate(GeoDiagnosisTaskReportEntity report) throws SQLException {
		// 1. 读取任务快照中的项目资料；确定判定渠道，首次冻结，续跑沿用已冻结的同一渠道
		JSONObject project = geoDiagnosisTaskReportService.getTaskSnapshot(report.getTaskId()).getJSONObject("project");
		GeoLlmChannelEntity channel = judgeChannel(report);
		if (channel == null) {
			return;
		}
		// 2. 逐条判定剩余待判定记录，失败只记录本条原因并继续
		Set<String> publishedUrls = geoDiagnosisTaskReportService.listPublishedUrls();
		while (! stopped && ! Thread.currentThread().isInterrupted()) {
			if (lease == null || ! lease.isValid(2)) {
				throw new IllegalStateException("诊断报告执行锁连接已失效");
			}
			GeoDiagnosisTaskResultAnalysisEntity analysis = geoDiagnosisTaskReportService.findNextPendingAnalysis(report.getId());
			if (analysis == null) {
				break;
			}
			resultAnalyzer.analyzeResult(analysis, project, publishedUrls, channel);
		}
		if (stopped || Thread.currentThread().isInterrupted()) {
			throw new IllegalStateException("诊断报告执行服务正在停止");
		}
		// 3. 全部判定结束后汇总并冻结
		summarize(report, project, channel);
	}

	/**
	 * 确定本报告的判定渠道：未冻结时选默认渠道（分数最高）并冻结，已冻结时按快照 ID 读取同一渠道。
	 *
	 * @param report 报告
	 * @return 判定渠道；无法确定时报告已标记失败并返回 null
	 */
	private GeoLlmChannelEntity judgeChannel(GeoDiagnosisTaskReportEntity report) {
		try {
			if (report.getJudgeChannelSnapshot() == null) {
				GeoLlmChannelEntity channel = router.selectDefaultChannel();
				geoDiagnosisTaskReportService.freezeJudgeChannel(report.getId(), JSON.toJSONString(diagnosisTaskSnapshots.buildChannelSnapshot(channel), SerializerFeature.WriteMapNullValue),
						ResultJudgePrompt.PROMPT_VERSION);
				report.setJudgeChannelSnapshot(JSON.toJSONString(diagnosisTaskSnapshots.buildChannelSnapshot(channel), SerializerFeature.WriteMapNullValue));
				report.setPromptVersion(ResultJudgePrompt.PROMPT_VERSION);
				log.info("诊断报告判定渠道已冻结 reportId={}, channel={}, model={}", report.getId(), channel.getChannelCode(), channel.getModelName());
				return channel;
			}
            // 历史执行过，则选用历史的大模型通道快照再执行
			Long channelId = ((JSONObject) diagnosisTaskSnapshots.parseSnapshot(report.getJudgeChannelSnapshot())).getLong("id");
			GeoLlmChannelEntity channel = channelService.getById(channelId);
			if (channel == null) {
				geoDiagnosisTaskReportService.failReport(report.getId(), "判定渠道已被删除，请重新生成报告");
				return null;
			}
			return channel;
		} catch (LLMClientException exception) {
			// 没有已启用渠道时无法判定，报告直接失败，不等待配置
			geoDiagnosisTaskReportService.failReport(report.getId(), "没有已启用的模型渠道，无法判定");
			return null;
		}
	}

	/**
	 * 归一维度并汇总，再生成优化建议，一起冻结到报告；全部判定失败或汇总异常时报告失败，避免反复重试产生费用。
	 * <p>优化建议生成失败不影响报告成功，summary.advice 记为失败，规则发现的问题照常保存。</p>
	 *
	 * @param report  报告
	 * @param project 项目资料
	 * @param channel 判定渠道，用于维度归一与优化建议调用
	 */
	private void summarize(GeoDiagnosisTaskReportEntity report, JSONObject project, GeoLlmChannelEntity channel) {
		try {
			// 1. 判定全部失败时没有可汇总的指标
			List<ReportAnswer> answers = geoDiagnosisTaskReportService.loadAnswers(report.getId());
			boolean anyJudged = answers.stream().anyMatch(answer -> DiagnosisTaskResultAnalysisStatus.SUCCEEDED.name().equals(answer.analysis().getStatus()));
			if (! anyJudged) {
				geoDiagnosisTaskReportService.failReport(report.getId(), "全部回答判定失败，无法生成报告");
				return;
			}
			// 2. 维度归一（非核心，失败时保持原名）后计算指标并冻结
			Map<String, String> mapping = reportLlmAnalyzer.normalizeDimensions(answers, channel, report.getId());
			JSONObject summary = reportSummarizer.buildSummary(answers, project, mapping,
					(JSONObject) diagnosisTaskSnapshots.parseSnapshot(report.getJudgeChannelSnapshot()), report.getPromptVersion());
			// 3. 基于规则发现的问题生成优化建议（非核心，失败时只记录在 advice 上），然后冻结
			summary.put("advice", reportLlmAnalyzer.generateAdvice(summary.getJSONArray("issues"), project, channel, report.getId()));
			geoDiagnosisTaskReportService.finishReport(report.getId(), JSON.toJSONString(summary, SerializerFeature.WriteMapNullValue));
			log.info("诊断报告生成完成 reportId={}, answers={}", report.getId(), answers.size());
		} catch (RuntimeException exception) {
			// 汇总是确定性计算，重试只会重复失败并重复维度归一调用，因此直接结束
			log.error("诊断报告汇总失败 reportId={}, errorType={}", report.getId(), exception.getClass().getSimpleName(), exception);
			geoDiagnosisTaskReportService.failReport(report.getId(), "报告汇总失败，请联系管理员");
		}
	}

	/**
	 * 取得数据库会话锁后才允许判定，其他实例只提供页面与接口。
	 *
	 * @return 当前实例是否持有执行锁
	 * @throws SQLException 连接检查或数据库锁申请失败
	 */
	private boolean acquireLease() throws SQLException {
		// 1. 复用有效的持锁会话，失效连接先归还连接池
		if (lease != null && lease.isValid(2)) {
			return true;
		}
		if (lease != null) {
			lease.close();
			lease = null;
		}
		// 2. 用独立会话非阻塞申请同库锁
		Connection candidate = dataSource.getConnection();
		try (var statement = candidate.prepareStatement("SELECT GET_LOCK(CONCAT(DATABASE(),?),0)")) {
			statement.setString(1, LOCK_NAME);
			try (var rows = statement.executeQuery()) {
				if (rows.next() && rows.getInt(1) == 1) {
					lease = candidate;
					return true;
				}
			}
		} finally {
			// 3. 未取得锁的候选连接立即归还，持锁连接保留至执行线程退出
			if (lease != candidate) {
				candidate.close();
			}
		}
		return false;
	}

	/**
	 * 停止本组件轮询，不关闭由 Spring 统一管理的线程池。
	 */
	@PreDestroy
	public void close() {
		// 先禁止继续领取，再中断本组件线程；数据库锁留给执行线程退出时释放
		stopped = true;
		Thread thread = workerThread;
		if (thread != null) {
			thread.interrupt();
		}
	}

	/**
	 * 执行线程退出后释放会话锁，未完成的报告由下次启动续跑。
	 */
	private void releaseLease() {
		try {
			if (lease != null) {
				// 1. 显式释放当前数据库会话持有的执行锁
				try (var statement = lease.prepareStatement("SELECT RELEASE_LOCK(CONCAT(DATABASE(),?))")) {
					statement.setString(1, LOCK_NAME);
					statement.execute();
				} finally {
					// 2. 即使释放锁语句失败，也关闭连接并清除本组件引用
					lease.close();
					lease = null;
				}
			}
		} catch (SQLException exception) {
			log.warn("诊断报告执行锁关闭失败 errorType={}", exception.getClass().getSimpleName());
		}
	}
}
