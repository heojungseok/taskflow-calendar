package com.taskflow.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * 메인 클래스에 붙어 있으면 @WebMvcTest 슬라이스가 JPA 메타모델을 요구해 뜨지 못한다.
 * 감사 설정만 따로 떼어낸다.
 */
@Configuration
@EnableJpaAuditing
public class JpaAuditingConfig {
}
