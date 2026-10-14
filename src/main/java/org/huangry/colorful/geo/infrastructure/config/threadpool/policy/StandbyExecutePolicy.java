package org.huangry.colorful.geo.infrastructure.config.threadpool.policy;

import lombok.AllArgsConstructor;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author huangry
 * Created in 2025/4/18 15:01
 */
@AllArgsConstructor
public class StandbyExecutePolicy implements RejectedExecutionHandler {

	public final ExecutorService executor;

	@Override
	public void rejectedExecution(Runnable r, ThreadPoolExecutor executor) {
		if (! executor.isShutdown()) {
			executor.submit(r);
		}
	}
}
