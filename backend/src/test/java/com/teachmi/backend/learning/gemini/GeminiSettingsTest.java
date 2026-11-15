package com.teachmi.backend.learning.gemini;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class GeminiSettingsTest {
    @Test void largerApplicationQuotasMatchYamlAndPreserveExplicitOlderLimits() throws Exception {
        var env=new MockEnvironment();
        var defaults=GeminiSettings.from(env);
        assertThat(defaults.requestsPerMinute()).isEqualTo(20);
        assertThat(defaults.requestsPerDay()).isEqualTo(500);
        assertThat(defaults.memberRequestsPerDay()).isEqualTo(100);
        for(var source:new YamlPropertySourceLoader().load("application",new ClassPathResource("application.yml")))
            env.getPropertySources().addLast(source);
        var yaml=GeminiSettings.from(env);
        assertThat(yaml.requestsPerMinute()).isEqualTo(defaults.requestsPerMinute());
        assertThat(yaml.requestsPerDay()).isEqualTo(defaults.requestsPerDay());
        assertThat(yaml.memberRequestsPerDay()).isEqualTo(defaults.memberRequestsPerDay());
        env.setProperty("GEMINI_REQUESTS_PER_MINUTE","10");
        env.setProperty("GEMINI_REQUESTS_PER_DAY","100");
        env.setProperty("GEMINI_MEMBER_REQUESTS_PER_DAY","20");
        var explicit=GeminiSettings.from(env);
        assertThat(explicit.requestsPerMinute()).isEqualTo(10);
        assertThat(explicit.requestsPerDay()).isEqualTo(100);
        assertThat(explicit.memberRequestsPerDay()).isEqualTo(20);
        assertThat(explicit.maxAttempts()).isEqualTo(2);
    }
    @Test void codeAndApplicationDefaultsUseTheSameOperationalDeadline() throws Exception {
        assertThat(GeminiSettings.from(new MockEnvironment()).requestTimeout()).isEqualTo(Duration.ofSeconds(40));
        var env=new MockEnvironment();
        for(var source:new YamlPropertySourceLoader().load("application",new ClassPathResource("application.yml")))
            env.getPropertySources().addLast(source);
        assertThat(GeminiSettings.from(env).requestTimeout()).isEqualTo(Duration.ofSeconds(40));
        env.setProperty("GEMINI_REQUEST_TIMEOUT_SECONDS","20");
        assertThat(GeminiSettings.from(env).requestTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test void explicitShorterDeadlineIsHonoredAndDiagnosedWithoutExposingSecrets(CapturedOutput output) {
        var settings=new GeminiConfiguration().geminiSettings(new MockEnvironment()
                .withProperty("teachmi.gemini.request-timeout-seconds","20")
                .withProperty("teachmi.gemini.api-key","private-credential-sentinel")
                .withProperty("teachmi.gemini.model","private-model-sentinel"));
        assertThat(settings.requestTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(output.getAll()).contains("requestTimeoutMs=20000","REQUEST_TIMEOUT_BELOW_OPERATIONAL_BASELINE")
                .doesNotContain("private-credential-sentinel","private-model-sentinel");
    }

    @Test void defaultDeadlineIsReportedWithoutShortDeadlineWarning(CapturedOutput output) {
        new GeminiConfiguration().geminiSettings(new MockEnvironment());
        assertThat(output.getAll()).contains("requestTimeoutMs=40000")
                .doesNotContain("REQUEST_TIMEOUT_BELOW_OPERATIONAL_BASELINE");
    }

    @Test void invalidDeadlineStillFailsInsteadOfSilentlyClamping() {
        assertThatThrownBy(() -> GeminiSettings.from(new MockEnvironment()
                .withProperty("teachmi.gemini.request-timeout-seconds","61")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
