package org.huangry.colorful.geo.infrastructure.config.threadpool;

import org.huangry.colorful.geo.infrastructure.common.utils.MDCUtil;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;

/**
 *
 * @author huangry
 * Created in 2026/10/14 10:31
 */
public class ThreadPoolMDCExecutor extends ThreadPoolTaskExecutor {
	@Override
	public void execute(Runnable task) {
		super.execute(MDCUtil.wrap(task, MDC.getCopyOfContextMap()));
	}

	@Override
	public Future<?> submit(Runnable task) {
		return super.submit(MDCUtil.wrap(task, MDC.getCopyOfContextMap()));
	}

	@Override
	public <T> Future<T> submit(Callable<T> task) {
		return super.submit(MDCUtil.wrap(task, MDC.getCopyOfContextMap()));
	}

}
