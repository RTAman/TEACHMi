package com.teachmi.backend.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 서비스 전체에서 1시간 동안 만들 수 있는 게스트 계정 수를 제한한다.
 * 프론트(Vercel)가 /api를 대신 전달하므로 사용자 IP를 믿을 수 없어 전체 개수로 막는다.
 * 시연용 단일 서버 기준의 메모리 구현이다.
 */
@Component
class GuestThrottle {
    static final Duration WINDOW = Duration.ofHours(1);
    private final AuthProperties properties;
    private final Clock clock;
    private final Deque<Instant> issued = new ArrayDeque<>();

    @Autowired
    GuestThrottle(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    GuestThrottle(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** 한도 안이면 한 자리를 차지하고 true, 한도를 넘으면 false. */
    synchronized boolean tryAcquire() {
        var now = clock.instant();
        var cutoff = now.minus(WINDOW);
        while (!issued.isEmpty() && !issued.peekFirst().isAfter(cutoff)) {
            issued.removeFirst();
        }
        if (issued.size() >= properties.guestMaxPerHour()) {
            return false;
        }
        issued.addLast(now);
        return true;
    }
}
