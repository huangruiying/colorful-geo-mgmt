package org.huangry.colorful.geo.infrastructure.common.utils;

import org.slf4j.MDC;
import org.springframework.util.CollectionUtils;

import java.util.Map;
import java.util.concurrent.Callable;

/**
 * MDCUtil: 提供包装 Runnable 和 Callable 任务，传递 MDC 上下文。
 * 使用 MDC 上下文可以在多线程任务中传递日志上下文（如 traceId、userId）。
 *
 * @author huangry
 * Created in 2025/4/23 14:32
 */
public class MDCUtil {

	/**
	 * 包装 Callable，传递 MDC 上下文
	 *
	 * @param c       需要包装的 Callable
	 * @param context MDC 上下文数据
	 * @param <T>     返回类型
	 * @return 包装后的 Callable
	 */
	public static <T> Callable<T> wrap(final Callable<T> c, final Map<String, String> context) {
		return () -> {
			try {
				setContext(context); // 设置 MDC 上下文
				return c.call();
			} finally {
				MDC.clear(); // 确保清理 MDC
			}
		};
	}

	/**
	 * 包装 Runnable，传递 MDC 上下文
	 *
	 * @param r       需要包装的 Runnable
	 * @param context MDC 上下文数据
	 * @return 包装后的 Runnable
	 */
	public static Runnable wrap(final Runnable r, final Map<String, String> context) {
		return () -> {
			try {
				setContext(context); // 设置 MDC 上下文
				r.run(); // 执行任务
			} finally {
				MDC.clear(); // 确保清理 MDC
			}
		};
	}

	/**
	 * 设置 MDC 上下文，若传入的上下文为空，则清除 MDC。
	 *
	 * @param context MDC 上下文数据
	 */
	private static void setContext(Map<String, String> context) {
		if (CollectionUtils.isEmpty(context)) {
			MDC.clear(); // 若上下文为空，则清除 MDC
		} else {
			MDC.setContextMap(context); // 设置 MDC 上下文
		}
	}
}
