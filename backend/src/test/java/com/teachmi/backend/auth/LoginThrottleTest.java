package com.teachmi.backend.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.assertThat;

class LoginThrottleTest {
    private final TestClock clock = new TestClock();
    private final AuthProperties properties = new AuthProperties("TEACHMI_SESSION", true, "Lax",
            Duration.ofDays(7), 5, Duration.ofMinutes(15));

    @Test void expiredEmailsAreRemovedEvenWhenTheyAreNeverRequestedAgain() {
        var throttle = new LoginThrottle(properties, clock, 2);
        throttle.recordFailure("old-a"); throttle.recordFailure("old-b");
        assertThat(throttle.isBlocked("new")).isTrue();
        clock.advance(Duration.ofMinutes(15));
        assertThat(throttle.isBlocked("new")).isFalse();
        assertThat(entries(throttle)).isEmpty();
        throttle.recordFailure("new");
        assertThat(entries(throttle)).containsOnlyKeys("new");
    }

    @Test void aFullCacheDoesNotGrowOrEvictAnActiveLimitAndExistingUsersCanStillLogIn() {
        var throttle = new LoginThrottle(properties, clock, 2);
        for (int i = 0; i < 5; i++) throttle.recordFailure("blocked");
        throttle.recordFailure("existing");
        for (int i = 0; i < 100; i++) {
            assertThat(throttle.isBlocked("unknown-" + i)).isTrue();
            throttle.recordFailure("unknown-" + i);
        }
        assertThat(entries(throttle)).hasSize(2);
        assertThat(throttle.isBlocked("blocked")).isTrue();
        assertThat(throttle.isBlocked("existing")).isFalse();
        throttle.reset("existing");
        assertThat(throttle.isBlocked("new")).isFalse();
    }

    @Test void failureCountsUseARollingWindowAndSuccessfulLoginResetsThem() {
        var throttle = new LoginThrottle(properties, clock, 4);
        for (int i = 0; i < 4; i++) throttle.recordFailure("one");
        assertThat(throttle.isBlocked("one")).isFalse();
        clock.advance(Duration.ofMinutes(1)); throttle.recordFailure("one");
        assertThat(throttle.isBlocked("one")).isTrue();
        clock.advance(Duration.ofMinutes(14));
        assertThat(throttle.isBlocked("one")).isFalse();
        assertThat(entries(throttle).get("one")).hasSize(1);
        throttle.reset("one");
        assertThat(entries(throttle)).isEmpty();
    }

    @Test void inFlightFailuresKeepBothQueueAndIdentityStorageBounded() throws Exception {
        var throttle = new LoginThrottle(properties, clock, 2);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 80; i++) tasks.add(executor.submit(() -> throttle.recordFailure("same")));
            for (var task : tasks) task.get(5, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(entries(throttle)).hasSize(1);
        assertThat(entries(throttle).get("same")).hasSize(5);
        assertThat(throttle.isBlocked("same")).isTrue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Deque<Instant>> entries(LoginThrottle throttle) {
        return (Map<String, Deque<Instant>>) ReflectionTestUtils.getField(throttle, "failures");
    }

    static class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-06T00:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
    }
}
