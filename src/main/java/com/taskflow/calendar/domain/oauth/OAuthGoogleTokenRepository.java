package com.taskflow.calendar.domain.oauth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OAuthGoogleTokenRepository extends JpaRepository<OAuthGoogleToken, Long> {
    Optional<OAuthGoogleToken> findByUserId(Long userId);
}