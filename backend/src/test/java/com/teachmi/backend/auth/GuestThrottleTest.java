package com.teachmi.backend.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GuestThrottleTest {
    @Test
    void limitsGuestAccountsPerHourAcrossTheWholeService() {
        var clock = new MutableClock();
        var throttle = new GuestThrottle(properties(true, 2), clock);
        assertThat(throttle.tryAcquire()).isTrue();
        clock.advance(Duration.ofMinutes(30));
        assertThat(throttle.tryAcquire()).isTrue();
        assertThat(throttle.tryAcquire()).isFalse();

        clock.advance(Duration.ofMinutes(30));
        assertThat(throttle.tryAcquire()).as("한 시간이 지난 첫 자리는 다시 쓸 수 있다").isTrue();
        assertThat(throttle.tryAcquire()).isFalse();
    }

    @Test
    void guestLoginCanBeTurnedOffOrRunOutWithoutTouchingTheDatabase() {
        var off = properties(false, 100);
        assertThatThrownBy(() -> service(off, new GuestThrottle(off)).guest())
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(404);
                    assertThat(e.code()).isEqualTo("GUEST_LOGIN_DISABLED");
                });

        var on = properties(true, 1);
        var full = new GuestThrottle(on);
        assertThat(full.tryAcquire()).isTrue();
        assertThatThrownBy(() -> service(on, full).guest())
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.status()).isEqualTo(429);
                    assertThat(e.code()).isEqualTo("TOO_MANY_GUESTS");
                });
    }

    @Test
    void missingSettingsKeepGuestLoginOnWithTheDefaultLimit() {
        var defaults = new AuthProperties(null, true, null, null, 0, null, null, 0);
        assertThat(defaults.guestEnabled()).isTrue();
        assertThat(defaults.guestMaxPerHour()).isEqualTo(100);
    }

    private static AuthService service(AuthProperties properties, GuestThrottle guests) {
        // JdbcTemplate 없이 만든다: 거절은 DB에 닿기 전에 끝나야 한다.
        return new AuthService(null, new BCryptPasswordEncoder(4), properties, new LoginThrottle(properties), guests);
    }

    private static AuthProperties properties(boolean enabled, int maxPerHour) {
        return new AuthProperties("TEACHMI_SESSION", true, "Lax", Duration.ofDays(7), 5, Duration.ofMinutes(15),
                enabled, maxPerHour);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-14T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
