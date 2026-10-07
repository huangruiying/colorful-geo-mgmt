package org.huangry.colorful.geo.infrastructure.client.playwright;

import org.huangry.colorful.geo.infrastructure.common.exceptions.PlatformBrowserLoginException;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

/**
 * 浏览器操作的串行执行器：统一单线程守护执行、固定超时与异常映射。
 *
 * <p>浏览器登录客户端共用本类，避免重复维护执行器、超时逻辑与异常包装。
 * 平台登录异常原样向上传递，其余异常交由调用方通过 onFailure 映射。</p>
 */
public class BrowserTaskRunner {

    private static final long TASK_TIMEOUT_SECONDS = 45;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 10;

    private final ScheduledExecutorService executor;

    /**
     * 创建单线程守护执行器。
     *
     * @param threadName 浏览器工作线程名，便于在日志与线程栈中区分不同客户端
     */
    public BrowserTaskRunner(String threadName) {
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, threadName);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 在串行浏览器线程执行动作；中断、超时与执行异常统一映射为调用方指定的领域异常。
     *
     * @param action 本次浏览器动作
     * @param onFailure 非领域异常时的异常工厂（按异常类型选择文案）
     * @return 动作结果
     */
    public <T> T execute(Callable<T> action, Function<Throwable, RuntimeException> onFailure) {
        try {
            return executor.submit(action).get(TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw onFailure.apply(exception);
        } catch (TimeoutException exception) {
            throw onFailure.apply(exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof PlatformBrowserLoginException loginException) throw loginException;
            throw onFailure.apply(cause);
        }
    }

    /**
     * 安排一次延迟任务，用于登录态生命周期到期清理。
     *
     * @param command 延迟执行的任务
     * @param delay 延迟时长
     * @param unit 时间单位
     */
    public void schedule(Runnable command, long delay, TimeUnit unit) {
        executor.schedule(command, delay, unit);
    }

    /**
     * 进程退出时关闭当前浏览器任务：先尝试优雅关闭，再强制关停。
     *
     * @param closeAction 关闭当前浏览器会话的动作
     */
    public void shutdown(Runnable closeAction) {
        try {
            executor.submit(closeAction).get(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // 退出时不记录可能包含浏览器会话细节的异常。
        } finally {
            executor.shutdownNow();
        }
    }
}
