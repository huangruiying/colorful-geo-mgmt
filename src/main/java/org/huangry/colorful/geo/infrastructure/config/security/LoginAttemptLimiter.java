package org.huangry.colorful.geo.infrastructure.config.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按客户端 IP 限制登录失败次数，防止暴力猜测密码。
 * <p>同一 IP 在 15 分钟内连续失败 5 次后锁定 15 分钟，锁定期间不再校验密码；登录成功清零。
 * 计数只保存在本进程内存中，重启后清空，不跨实例共享；不负责账号校验本身。</p>
 */
@Slf4j
@Component
public class LoginAttemptLimiter {
    /** 触发锁定的连续失败次数。 */
    static final int MAX_FAILURES = 5;
    /** 失败计数的有效期，同时也是锁定时长。 */
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);
    /** 记录的 IP 上限；超过时先清理过期记录，避免大量来源 IP 撑满内存。 */
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    /** IP 到失败记录；只保存最近一次失败窗口内的数据。 */
    private final Map<String, FailureRecord> failures = new ConcurrentHashMap<>();
    /** 时间来源，测试可替换为固定时钟。 */
    private final Clock clock;

    /** Spring 使用系统时钟创建。 */
    public LoginAttemptLimiter() {
        this(Clock.systemUTC());
    }

    /**
     * 使用指定时钟创建，供测试模拟时间流逝。
     * @param clock 时间来源
     */
    LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * 判断该 IP 当前是否处于锁定期。
     * @param clientIp 客户端 IP
     * @return 锁定中返回 true，此时调用方应直接拒绝登录，不校验密码
     */
    public boolean isLocked(String clientIp) {
        FailureRecord failure = failures.get(clientIp);
        return failure != null && !failure.isExpired(clock.instant()) && failure.count() >= MAX_FAILURES;
    }

    /**
     * 记录一次登录失败；窗口过期后重新计数，达到上限时开始锁定。
     * @param clientIp 客户端 IP
     */
    public void recordFailure(String clientIp) {
        Instant now = clock.instant();
        if (failures.size() >= MAX_TRACKED_CLIENTS) {
            failures.values().removeIf(failure -> failure.isExpired(now));
        }
        // 窗口内累加，过期后从 1 重新开始；锁定从最后一次失败起算
        FailureRecord failure = failures.compute(clientIp, (ip, previous) -> previous == null || previous.isExpired(now)
                ? new FailureRecord(1, now) : new FailureRecord(previous.count() + 1, now));
        if (failure.count() == MAX_FAILURES) {
            log.warn("后台登录连续失败已锁定 clientIp={} failures={} lockMinutes={}", clientIp, failure.count(), LOCK_DURATION.toMinutes());
        }
    }

    /**
     * 登录成功后清除该 IP 的失败记录。
     * @param clientIp 客户端 IP
     */
    public void recordSuccess(String clientIp) {
        failures.remove(clientIp);
    }

    /**
     * 一个 IP 的失败记录。
     * @param count 窗口内连续失败次数
     * @param lastFailureAt 最近一次失败时间，锁定与计数窗口都从这里起算
     */
    private record FailureRecord(int count, Instant lastFailureAt) {
        /**
         * 判断记录是否已失效。
         * @param now 当前时间
         * @return 距最近一次失败已超过锁定时长，记录不再生效
         */
        boolean isExpired(Instant now) {
            return !now.isBefore(lastFailureAt.plus(LOCK_DURATION));
        }
    }
}
