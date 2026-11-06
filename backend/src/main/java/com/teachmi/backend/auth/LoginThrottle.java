package com.teachmi.backend.auth;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 이메일별 로그인 실패 횟수를 일정 시간 동안 센다. 시연용 단일 서버 기준의 메모리 구현이며,
 * 서버를 여러 대로 늘리면 DB나 Redis로 옮겨야 한다.
 */
@Component
class LoginThrottle {
    private final AuthProperties properties;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    LoginThrottle(AuthProperties properties) {
        this.properties = properties;
    }

    boolean isBlocked(String key) {
        var queue = failures.get(key);
        if (queue == null) {
            return false;
        }
        synchronized (queue) {
            prune(queue);
            return queue.size() >= properties.loginMaxFailures();
        }
    }

    void recordFailure(String key) {
        var queue = failures.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        synchronized (queue) {
            prune(queue);
            queue.addLast(Instant.now());
        }
    }

    void reset(String key) {
        failures.remove(key);
    }

    private void prune(Deque<Instant> queue) {
        var cutoff = Instant.now().minus(properties.loginFailureWindow());
        while (!queue.isEmpty() && queue.peekFirst().isBefore(cutoff)) {
            queue.pollFirst();
        }
    }
}
