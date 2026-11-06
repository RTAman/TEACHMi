package com.teachmi.backend.auth;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AuthProperties.class)
class AuthConfiguration {
    /** 비밀번호 해시는 검증된 라이브러리(Spring Security Crypto)의 BCrypt를 사용한다. */
    @Bean
    BCryptPasswordEncoder authPasswordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
