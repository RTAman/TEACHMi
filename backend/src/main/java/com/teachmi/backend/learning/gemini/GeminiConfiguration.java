package com.teachmi.backend.learning.gemini;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import com.teachmi.backend.learning.LearningSourceReader;
import com.teachmi.backend.ai.GeminiGenerationPort;
import com.teachmi.backend.learning.LearningSessionService;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "teachmi.gemini.enabled", havingValue = "true")
public class GeminiConfiguration {
    @Bean GeminiSettings geminiSettings(Environment environment) {
        return GeminiSettings.from(environment);
    }
    @Bean @ConditionalOnMissingBean(GeminiTransport.class)
    GeminiTransport geminiTransport(GeminiSettings settings) {
        // The generator reports missing model/key configuration before making a request.
        return settings.model().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,99}")
                ? new GeminiHttpTransport(settings)
                : request -> { throw new GeminiCallFailure(false, 503, "GEMINI_CONFIGURATION_REQUIRED", 0); };
    }
    @Bean(destroyMethod = "close") GeminiLedger geminiLedger(DataSourceProperties database, GeminiSettings settings) {
        return new GeminiLedger(database, settings);
    }
    @Bean GeminiGenerationService geminiGenerationService(GeminiSettings settings,
            ObjectProvider<LearningSourceReader> sources, GeminiTransport transport,
            GeminiLedger ledger, ObjectMapper json, ObjectProvider<LearningSessionService> sessions) {
        return new GeminiGenerationService(settings, sources, transport, ledger, json, sessions);
    }
    @Bean @ConditionalOnMissingBean(GeminiGenerationPort.class)
    GeminiGenerationPort geminiGenerationPort(GeminiGenerationService service) {
        return new GeminiPortAdapter(service);
    }
    @Bean GeminiContentGenerator geminiContentGenerator(GeminiGenerationService service, GeminiSettings settings, ObjectMapper json) {
        return new GeminiContentGenerator(service, settings, json);
    }
}
