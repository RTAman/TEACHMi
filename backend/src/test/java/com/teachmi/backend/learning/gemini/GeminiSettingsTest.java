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
