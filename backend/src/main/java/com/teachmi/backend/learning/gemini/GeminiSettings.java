package com.teachmi.backend.learning.gemini;

import java.time.Duration;
import org.springframework.core.env.Environment;

record GeminiSettings(String apiKey, String model, Duration connectTimeout, Duration requestTimeout,
                      int requestsPerMinute, int requestsPerDay, int memberRequestsPerDay,
                      int maxAttempts, int maxSourceChars, int maxOutputTokens) {
    static GeminiSettings from(Environment env) {
        return new GeminiSettings(env.getProperty("teachmi.gemini.api-key", ""),
                env.getProperty("teachmi.gemini.model", ""),
                Duration.ofSeconds(number(env, "connect-timeout-seconds", 3, 1, 10)),
                Duration.ofSeconds(number(env, "request-timeout-seconds", 20, 1, 60)),
                number(env, "requests-per-minute", 10, 1, 1000),
                number(env, "requests-per-day", 100, 1, 10000),
                number(env, "member-requests-per-day", 20, 1, 1000),
                number(env, "max-attempts", 2, 1, 3),
                number(env, "max-source-chars", 16000, 100, 100000),
                number(env, "max-output-tokens", 2048, 256, 8192));
    }

    private static int number(Environment env, String key, int fallback, int min, int max) {
        int value = env.getProperty("teachmi.gemini." + key, Integer.class, fallback);
        if (value < min || value > max) {
            throw new IllegalArgumentException("Invalid Gemini limit setting: " + key);
        }
        return value;
    }

    @Override public String toString() {
        return "GeminiSettings[credentials redacted]";
    }
}
