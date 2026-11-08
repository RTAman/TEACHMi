package com.teachmi.backend.material;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.unit.DataSize;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MaterialConfiguration.MaterialProperties.class)
class MaterialConfiguration {
    @ConfigurationProperties("teachmi.materials")
    record MaterialProperties(DataSize maxFileSize) {
        MaterialProperties {
            if (maxFileSize == null || maxFileSize.toBytes() <= 0) {
                maxFileSize = DataSize.ofMegabytes(20);
            }
        }
    }

    /** 자료 분석 전용 스레드. 요청 스레드와 분리해 사용자가 화면을 닫아도 분석이 계속된다. */
    @Bean(name = "materialAnalysisExecutor", destroyMethod = "shutdown")
    ThreadPoolTaskExecutor materialAnalysisExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("material-analysis-");
        executor.initialize();
        return executor;
    }
}
