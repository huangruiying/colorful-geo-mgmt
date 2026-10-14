package org.huangry.colorful.geo.presentation.task;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.domain.service.diagnosis.GeoDiagnosisTaskService;
import org.huangry.colorful.geo.domain.service.diagnosis.DiagnosisTaskSnapshots;
import org.huangry.colorful.geo.infrastructure.client.llm.LLMClient;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMQueryRequest;
import org.huangry.colorful.geo.infrastructure.client.llm.model.LLMResponse;
import org.huangry.colorful.geo.infrastructure.common.exceptions.LLMClientException;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanExecRecordEntity;
import org.huangry.colorful.geo.infrastructure.repository.entity.GeoDiagnosisPlanExecRecordResultEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

/**
 * 诊断任务离线 Job：从数据库领取任务，逐条调用模型并保存回答。
 * <p>start 向统一线程池提交单个轮询，tick 领取整轮任务，execute 执行采样，close 通知退出。
 * 数据库会话锁限定同一库唯一执行者；不负责页面查询，不自动重试模型调用，不管理共享线程池。</p>
 */
@Component
@Slf4j
public class DiagnosisTaskWorker {
	@Resource
	private GeoDiagnosisTaskService service;
	@Resource
	private DiagnosisTaskSnapshots snapshots;
	@Resource
	private LLMClient llmClient;
	@Resource
	private DataSource dataSource;
	@Value("${diagnosis.tasks.enabled:true}")
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
	 * 本次取得执行锁后是否已完成中断任务恢复。
	 */
	private boolean recovered;
	/**
	 * 停机后禁止领取新任务和新采样。
	 */
	private volatile boolean stopped;
	/**
	 * 执行未正常结束的任务，数据库恢复后先终止其剩余采样。
	 */
	private Long interruptedTaskId;
	/**
	 * 基础设施故障后的下次轮询时间，使用单调时钟纳秒值。
	 */
	private long nextAttemptAt;

	/**
	 * 应用启动完成后向统一线程池提交唯一轮询任务，浏览器关闭不会取消执行。
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (enabled && ! stopped && started.compareAndSet(false, true)) {
			taskExecutor.execute(this::poll);
		}
	}

	/**
	 * 顺序轮询；执行结束后等待一秒，退出时由执行线程释放数据库锁。
	 */
	private void poll() {
		workerThread = Thread.currentThread();
		try {
			// 1. 首次及每轮执行结束后间隔一秒，停机或线程中断时退出。
			while (! stopped && ! Thread.currentThread().isInterrupted()) {
				TimeUnit.SECONDS.sleep(1);
				if (! stopped) {
					// 2. 顺序完成本轮任务处理，再等待下一次轮询。
					tick();
				}
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} finally {
			// 3. 远程调用真正结束后才释放锁，避免其他实例提前接管。
			releaseLease();
			workerThread = null;
		}
	}

	/**
	 * 隔离一次任务故障；持久化失败后先终止旧任务，再处理新任务。
	 */
	private void tick() {
		// 1. 停机或故障等待期间不领取任务。
		if (stopped || System.nanoTime() < nextAttemptAt) {
			return;
		}
		try {
			// 2. 先取得同库执行权，未持锁的实例不恢复任务、不调用模型。
			if (! acquireLease()) {
				return;
			}
			if (! recovered) {
				// 3. 首次接管时处理上次退出遗留的执行中任务，避免重复发出不确定调用。
				service.recoverInterrupted();
				recovered = true;
			}
			if (interruptedTaskId != null) {
				// 4. 上轮执行或落库异常时，先终止剩余采样，再领取新任务。
				service.interrupt(interruptedTaskId, "执行或结果保存中断，未完成样本不自动重发；可重新发起整轮诊断");
				interruptedTaskId = null;
			}
			// 5. 领取一轮任务，正常执行完成后清除中断标识。
			GeoDiagnosisPlanExecRecordEntity task = service.claimNext();
			if (task != null) {
				interruptedTaskId = task.getId();
				execute(task.getId());
				interruptedTaskId = null;
			}
		} catch (RuntimeException | SQLException exception) {
			// 基础设施故障等待五秒再轮询；不重发模型请求，异常日志不带远端原文。
			log.error("诊断后台执行中断 taskId={}, errorType={}", interruptedTaskId, exception.getClass().getSimpleName());
			nextAttemptAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		}
	}

	/**
	 * 逐条执行独立问题，保存完成后才领取下一条。
	 *
	 * @param taskId 已领取的任务
	 * @throws SQLException 执行锁连接检查失败，交由轮询处理整轮中断
	 */
	private void execute(long taskId) throws SQLException {
		while (! stopped && ! Thread.currentThread().isInterrupted()) {
			// 1. 每次领取前确认执行锁连接有效，避免失去执行权后继续调用模型。
			if (lease == null || ! lease.isValid(2)) {
				throw new IllegalStateException("诊断执行锁连接已失效");
			}
			// 2. 领取一条采样；没有剩余采样时汇总整轮终态。
			GeoDiagnosisPlanExecRecordResultEntity sample = service.claimSample(taskId);
			if (sample == null) {
				service.finish(taskId);
				return;
			}
			long started = System.nanoTime();
			LLMResponse response;
			try {
				// 3. 按冻结渠道校验当前配置，只发送本条问题，不携带背景或历史回答。
				var channel = service.prepareChannel(sample);
				String prompt = snapshots.read(sample.getQuestionSnapshot()).path("text").asText();
				response = llmClient.query(new LLMQueryRequest(prompt), channel);
				// 返回空白正文视为采样失败，不把空回答保存为成功。
				if (response == null || response.getContent().isBlank()) {
					throw new LLMClientException("模型未返回有效回答");
				}
			} catch (LLMClientException exception) {
				// 模型失败仅记录本条原因，继续其他采样，不自动重试或切换渠道。
				service.fail(sample, failureReason(exception), elapsed(started));
				continue;
			} catch (IllegalArgumentException exception) {
				// 配置或问题校验失败同样终止本条采样，历史快照不按当前配置重写。
				service.fail(sample, "渠道配置已变化或问题无效，请重新创建诊断任务", elapsed(started));
				continue;
			}
			// 4. 保存成功回答；落库失败向外抛出，由 tick 终止剩余采样，不重复调用模型。
			service.succeed(sample, response, elapsed(started));
		}
		throw new IllegalStateException("诊断执行服务正在停止");
	}

	/**
	 * 取得数据库会话锁后才允许恢复与调用，其他实例只提供页面/API。
	 *
	 * @return 当前实例是否持有执行锁
	 * @throws SQLException 连接检查或数据库锁申请失败
	 */
	private boolean acquireLease() throws SQLException {
		// 1. 复用有效的持锁会话，失效连接先归还连接池。
		if (lease != null && lease.isValid(2)) {
			return true;
		}
		if (lease != null) {
			lease.close();
			lease = null;
		}
		// 2. 用独立会话非阻塞申请同库锁，获得锁后重新执行中断恢复。
		Connection candidate = dataSource.getConnection();
		try (var statement = candidate.prepareStatement("SELECT GET_LOCK(CONCAT(DATABASE(),':diagnosis-worker'),0)")) {
			try (var rows = statement.executeQuery()) {
				if (rows.next() && rows.getInt(1) == 1) {
					lease = candidate;
					recovered = false;
					return true;
				}
			}
		} finally {
			// 3. 未取得锁的候选连接立即归还，持锁连接保留至执行线程退出。
			if (lease != candidate) {
				candidate.close();
			}
		}
		return false;
	}

	/**
	 * 以异常类型给出安全建议，不持久化远端响应或认证信息。
	 *
	 * @param exception 模型调用异常
	 * @return 页面可展示的原因
	 */
	private String failureReason(LLMClientException exception) {
		for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
			// 按外层到根因检查连接类异常，返回可操作原因，不展示异常原文。
			if (cause instanceof javax.net.ssl.SSLException) {
				return "HTTPS 连接失败，请检查统一证书配置";
			}
			if (cause instanceof java.net.SocketTimeoutException) {
				return "模型调用超时，请检查统一 HTTP 超时配置";
			}
			if (cause instanceof java.net.ConnectException) {
				return "无法连接模型服务，请检查地址和网络";
			}
			if (cause instanceof org.springframework.web.client.HttpStatusCodeException http) {
				// 429 单独提示调用频率限制，其他 HTTP 拒绝提示权限与参数检查。
				return http.getStatusCode().value() == 429 ? "模型服务限制调用频率，本次不自动重试" : "模型服务拒绝请求，请检查渠道权限与生成参数";
			}
		}
		// 未识别的异常使用统一原因，不猜测厂商错误或泄露远端内容。
		return "渠道不可用或模型未返回有效回答，请检查模型配置";
	}

	/**
	 * 使用单调时钟记录耗时，不受系统时间调整影响。
	 *
	 * @param started 调用开始时的纳秒值
	 * @return 毫秒耗时
	 */
	private long elapsed(long started) {
		return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
	}

	/**
	 * 停止本组件轮询，不关闭由 Spring 统一管理的线程池。
	 */
	@PreDestroy
	public void close() {
		// 先禁止继续领取，再中断本组件线程；数据库锁留给执行线程退出时释放。
		stopped = true;
		Thread thread = workerThread;
		if (thread != null) {
			thread.interrupt();
		}
	}

	/**
	 * 执行线程退出后释放会话锁，未完成状态由下次启动恢复。
	 */
	private void releaseLease() {
		try {
			if (lease != null) {
				// 1. 显式释放当前数据库会话持有的执行锁。
				try (var statement = lease.prepareStatement("SELECT RELEASE_LOCK(CONCAT(DATABASE(),':diagnosis-worker'))")) {
					statement.execute();
				} finally {
					// 2. 即使释放锁语句失败，也关闭连接并清除本组件引用。
					lease.close();
					lease = null;
				}
			}
		} catch (SQLException exception) {
			log.warn("诊断执行锁关闭失败 errorType={}", exception.getClass().getSimpleName());
		}
	}
}
