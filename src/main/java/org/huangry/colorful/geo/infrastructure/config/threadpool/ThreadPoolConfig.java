package org.huangry.colorful.geo.infrastructure.config.threadpool;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.huangry.colorful.geo.infrastructure.config.threadpool.policy.OverflowRecordPolicy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 *
 * @author huangry
 * Created in 2026/10/14 10:32
 */
@Slf4j
@Data
@Configuration
public class ThreadPoolConfig {

	@Value("${thread.corePoolSize:8}")
	private int corePoolSize;

	@Value("${thread.keepAliveTime:30000}")
	private long keepAliveTime;

	@Value("${thread.maximumPoolSize:16}")
	private int maximumPoolSize;

	@Value("${thread.queueCapacity:100}")
	private int queueCapacity;

	/**
	 * 诊断任务处理线程池
	 */
	@Bean
	@Qualifier("diagnosisTaskExecutor")
	public ThreadPoolTaskExecutor diagnosisTaskExecutor() {

		ThreadPoolTaskExecutor executor = new ThreadPoolMDCExecutor();
		executor.setCorePoolSize(corePoolSize);
		executor.setMaxPoolSize(maximumPoolSize);
		executor.setKeepAliveSeconds((int) (keepAliveTime / 1000));
		executor.setQueueCapacity(500);
		executor.setThreadNamePrefix("诊断线程-");
		executor.setRejectedExecutionHandler(new OverflowRecordPolicy());
		executor.initialize();
		return executor;
	}
}
