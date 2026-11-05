package com.teachmi.backend.learning;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = LearningTestApplication.class)
class LearningUnavailableSourceTest {
    @Autowired WebApplicationContext context;

    @Test
    void missingAAdapterRejectsCreationInsteadOfTrustingClientConcepts() throws Exception {
        MockMvcBuilders.webAppContextSetup(context).build()
                .perform(post("/api/v1/sessions").principal(() -> "adapter-check")
                        .header("Idempotency-Key", "no-source")
                        .contentType("application/json")
                        .content("{\"materialId\":\"m01\",\"conceptId\":\"c01\"}"))
                .andExpect(status().isServiceUnavailable());
    }
}
