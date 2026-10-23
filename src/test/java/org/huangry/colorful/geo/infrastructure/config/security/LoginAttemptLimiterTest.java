package org.huangry.colorful.geo.infrastructure.config.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 登录失败限流：达到次数锁定、锁定到期解除、登录成功清零、不同 IP 互不影响。 */
class LoginAttemptLimiterTest {
    private static final String CLIENT_IP = "203.0.113.7";

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-21T08:00:00Z"));
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    });

    @Test
    void shouldLockOnlyAfterReachingMaxFailures() {
        failTimes(LoginAttemptLimiter.MAX_FAILURES - 1);
        assertFalse(limiter.isLocked(CLIENT_IP));

        failTimes(1);
        assertTrue(limiter.isLocked(CLIENT_IP));
        assertFalse(limiter.isLocked("198.51.100.1"));
    }

    @Test
    void shouldUnlockAfterLockDurationSinceLastFailure() {
        failTimes(LoginAttemptLimiter.MAX_FAILURES);

        now.set(now.get().plus(LoginAttemptLimiter.LOCK_DURATION).minus(Duration.ofSeconds(1)));
        assertTrue(limiter.isLocked(CLIENT_IP));
        now.set(now.get().plus(Duration.ofSeconds(1)));
        assertFalse(limiter.isLocked(CLIENT_IP));
    }

    @Test
    void shouldRestartCountingAfterWindowExpires() {
        failTimes(LoginAttemptLimiter.MAX_FAILURES - 1);
        now.set(now.get().plus(LoginAttemptLimiter.LOCK_DURATION));

        failTimes(1);
        assertFalse(limiter.isLocked(CLIENT_IP));
    }

    @Test
    void shouldClearFailuresAfterSuccessfulLogin() {
        failTimes(LoginAttemptLimiter.MAX_FAILURES - 1);
        limiter.recordSuccess(CLIENT_IP);

        failTimes(1);
        assertFalse(limiter.isLocked(CLIENT_IP));
    }

    private void failTimes(int times) {
        for (int index = 0; index < times; index++) {
            limiter.recordFailure(CLIENT_IP);
        }
    }
}
