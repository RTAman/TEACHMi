package com.teachmi.backend.learning;

import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Use B's production HTTP/service/persistence code without scanning A's future adapters.
 * This keeps ownership fixtures independent when A and C add their own components.
 */
@TestComponent
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@Import({LearningSessionController.class, LearningSessionService.class, LearningErrorHandler.class,
        LearningTurnService.class, LearningTurnTransactions.class, LearningReteachService.class,
        LearningReteachTransactions.class})
public class LearningTestApplication {}
