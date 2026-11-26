package com.taskflow.identity.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;

@SpringBootTest(properties = "outbox.worker.enabled=false")
class NoDefaultUserDetailsServiceTest {

    @Autowired
    ApplicationContext context;

    @Test
    @DisplayName("JWT 전용이라 임시 비밀번호를 만드는 기본 UserDetailsService를 띄우지 않는다")
    void noInMemoryUserDetailsService() {
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
    }
}
