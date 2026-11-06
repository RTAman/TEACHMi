package com.teachmi.backend.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 이메일별 로그인 실패 횟수를 일정 시간 동안 센다. 시연용 단일 서버 기준의 메모리 구현이며,
 * 서버를 여러 대로 늘리면 DB나 Redis로 옮겨야 한다.
 */
@Component
class LoginThrottle {
    static final int MAX_TRACKED_EMAILS = 10_000;
    private final AuthProperties properties;
    private final Clock clock;
    private final int maxTrackedEmails;
    private final Map<String, Deque<Instant>> failures = new LinkedHashMap<>();

    @Autowired
    LoginThrottle(AuthProperties properties) {
        this(properties, Clock.systemUTC(), MAX_TRACKED_EMAILS);
    }

    LoginThrottle(AuthProperties properties, Clock clock, int maxTrackedEmails) {
        if (maxTrackedEmails <= 0) throw new IllegalArgumentException("A positive cache bound is required");
        this.properties = properties;
        this.clock = clock;
        this.maxTrackedEmails = maxTrackedEmails;
    }

    synchronized boolean isBlocked(String key) {
        removeExpired(clock.instant());
        var queue = failures.get(key);
        // Do not evict active limits: rotating email addresses must not reset another user's limit.
        return queue == null ? failures.size() >= maxTrackedEmails : queue.size() >= properties.loginMaxFailures();
    }

    synchronized void recordFailure(String key) {
        var now = clock.instant();
        removeExpired(now);
        if (!failures.containsKey(key) && failures.size() >= maxTrackedEmails) return;
        var queue = failures.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        // Requests already checking a password can fail concurrently after the limit is reached.
        while (queue.size() >= properties.loginMaxFailures()) queue.removeFirst();
        queue.addLast(now);
    }

    synchronized void reset(String key) {
        failures.remove(key);
    }

    private void removeExpired(Instant now) {
        var cutoff = now.minus(properties.loginFailureWindow());
        var entries = failures.entrySet().iterator();
        while (entries.hasNext()) {
            var queue = entries.next().getValue();
            while (!queue.isEmpty() && !queue.peekFirst().isAfter(cutoff)) queue.removeFirst();
            if (queue.isEmpty()) entries.remove();
        }
    }
}
