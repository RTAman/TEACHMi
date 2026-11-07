package com.teachmi.backend.ai;

import com.teachmi.backend.TeachmiApplication;
import com.teachmi.backend.learning.LearningContentGenerator;
import com.teachmi.backend.learning.gemini.GeminiContentGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Consumer packages can replace the public port without knowing B's private transport or changing its generator. */
@SpringBootTest(classes=TeachmiApplication.class,properties={
        "teachmi.gemini.enabled=true","teachmi.gemini.api-key=replaceable-test-key","teachmi.gemini.model=replaceable-test-model"})
class GeminiPortReplacementTest {
    @MockitoBean GeminiGenerationPort port;
    @Autowired LearningContentGenerator bGenerator;
    @Test void replacingThePortKeepsTheExistingBGeneratorAndMakesNoProviderCall() {
        doReturn("replacement").when(port).generate(any(),any());
        String result=port.generate(null,node -> "unused");
        assertThat(result).isEqualTo("replacement");
        assertThat(bGenerator).isInstanceOf(GeminiContentGenerator.class);
        verify(port).generate(any(),any());
    }
}
