package org.huangry.colorful.geo.presentation.task;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.domain.service.content.ContentPublishRecordService;
import org.huangry.colorful.geo.domain.service.publish.ContentPublicationService;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationRequest;
import org.huangry.colorful.geo.infrastructure.client.publish.model.PublicationResult;
import org.huangry.colorful.geo.infrastructure.repository.entity.ContentPublishRecordEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 预发布后台 Job：领取待预发布的发布记录，逐条调用平台创建草稿并回写结果。
 * <p>用户提交预发布时只插入“初始化”记录（见 ContentPublishBusiness.prePublish），本 Job 每秒扫描一次，
 * 用带状态条件的更新领取记录（改为预发布中），保证同一条记录只调用一次平台；平台之间串行执行。</p>
 * <p>不负责页面查询，不自动重试平台调用：结果无法确认或回写失败的记录停在“预发布中”，表示需人工核对平台草稿箱。</p>
 */
@Component
@Slf4j
public class ContentPrePublishWorker {
	@Resource
	private ContentPublishRecordService publishRecordService;
	@Resource
	private ContentPublicationService publicationService;
	@Value("${content.prepublish.worker.enabled:true}")
	private boolean enabled;
	@Resource(name = "prePublishTaskExecutor")
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
	 * 停机后禁止领取新记录。
	 */
	private volatile boolean stopped;
	/**
	 * 数据库故障后的下次轮询时间，使用单调时钟纳秒值。
	 */
	private long nextAttemptAt;

	/**
	 * 应用启动完成后向专用线程池提交唯一轮询任务；开关关闭时提交的记录停在“初始化”，不调用平台。
	 */
	@EventListener(ApplicationReadyEvent.class)
	public void start() {
		if (enabled && !stopped && started.compareAndSet(false, true)) {
			taskExecutor.execute(this::poll);
		}
	}

	/**
	 * 顺序轮询，每轮之间等待一秒；停机或线程中断时退出。
	 */
	private void poll() {
		workerThread = Thread.currentThread();
		try {
			while (!stopped && !Thread.currentThread().isInterrupted()) {
				TimeUnit.SECONDS.sleep(1);
				if (!stopped) {
					tick();
				}
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		} finally {
			workerThread = null;
		}
	}

	/**
	 * 一轮处理完当前全部待预发布记录；领取时数据库故障则等待五秒再试。
	 */
	void tick() {
		// 1. 停机或故障等待期间不领取
		if (stopped || System.nanoTime() < nextAttemptAt) {
			return;
		}
		try {
			// 2. 逐条领取并执行，直到没有待预发布记录
			ContentPublishRecordEntity record;
			while (!stopped && (record = publishRecordService.claimNextInitializedRecord()) != null) {
				prePublishRecord(record);
			}
		} catch (RuntimeException exception) {
			// 领取失败（如数据库不可用）时没有调用任何平台，稍后重试领取是安全的
			log.error("预发布记录领取失败，errorType={}", exception.getClass().getSimpleName(), exception);
			nextAttemptAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		}
	}

	/**
	 * 为一条已领取的记录调用平台创建草稿，并回写平台结果。
	 * <p>平台的登录、校验、超时等异常已由 ContentPublicationService 转成失败或待核对结果；
	 * 回写失败只记日志，记录停在“预发布中”待人工核对，不影响后续记录。</p>
	 *
	 * @param record 已改为预发布中的发布记录，含确认稿标题与正文
	 */
	private void prePublishRecord(ContentPublishRecordEntity record) {
		try {
			// 1. 用记录里的确认稿快照调用该平台，不读取优化记录的当前内容
			PublicationRequest request = PublicationRequest.builder()
					.title(record.getPublicationTitle()).content(record.getPublicationContent())
					.platformTypes(List.of(record.getPlatformType())).build();
			List<PublicationResult> results = publicationService.prePublishContent(request);
			// 2. 回写平台结果；只更新预发布中的记录，迟到结果不覆盖已有结论
			for (PublicationResult result : results) {
				publishRecordService.savePrePublishResult(record.getContentOptimizationRecordId(), result);
			}
		} catch (RuntimeException exception) {
			// 平台可能已建稿，不能改成失败后重试；保留预发布中，由用户核对平台草稿箱
			log.error("预发布结果未能回写，publishRecordId={}，platformType={}，errorType={}",
					record.getId(), record.getPlatformType(), exception.getClass().getSimpleName(), exception);
		}
	}

	/**
	 * 停止本组件轮询，不关闭由 Spring 统一管理的线程池；正在执行的平台调用结束后线程退出。
	 */
	@PreDestroy
	public void close() {
		stopped = true;
		Thread thread = workerThread;
		if (thread != null) {
			thread.interrupt();
		}
	}
}
