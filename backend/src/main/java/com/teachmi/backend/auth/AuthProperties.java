package com.teachmi.backend.auth;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("teachmi.auth")
record AuthProperties(String cookieName, boolean cookieSecure, String cookieSameSite, Duration sessionTtl,
                      int loginMaxFailures, Duration loginFailureWindow, Boolean guestEnabled, int guestMaxPerHour) {
    AuthProperties {
        if (guestEnabled == null) {
            guestEnabled = true;
        }
        if (guestMaxPerHour <= 0) {
            guestMaxPerHour = 100;
        }
        if (cookieName == null || cookieName.isBlank()) {
            cookieName = "TEACHMI_SESSION";
        }
        if (cookieSameSite == null || cookieSameSite.isBlank()) {
            cookieSameSite = "Lax";
        }
        if (sessionTtl == null || sessionTtl.isNegative() || sessionTtl.isZero()) {
            sessionTtl = Duration.ofDays(7);
        }
        if (loginMaxFailures <= 0) {
            loginMaxFailures = 5;
        }
        if (loginFailureWindow == null || loginFailureWindow.isNegative() || loginFailureWindow.isZero()) {
            loginFailureWindow = Duration.ofMinutes(15);
        }
    }
}
