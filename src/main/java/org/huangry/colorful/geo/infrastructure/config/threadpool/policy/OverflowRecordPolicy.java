package org.huangry.colorful.geo.infrastructure.config.threadpool.policy;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 溢出记录策略
 *
 * @author huangry
 * Created in 2025/4/18 15:01
 */
public class OverflowRecordPolicy implements RejectedExecutionHandler {

	private static final Logger logger = LoggerFactory.getLogger(OverflowRecordPolicy.class);

	@Override
	public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
		logger.error("线程池执行溢出，线程记录 {}", r);
	}
}
