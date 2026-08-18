package com.taskflow.calendar.domain.oauth;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface OAuthGoogleTokenRepository extends JpaRepository<OAuthGoogleToken, Long> {
    Optional<OAuthGoogleToken> findByUserId(Long userId);

    /** 구글 연동 보유 여부. 데모/미연동 사용자를 구글 호출 전에 걸러낸다. */
    boolean existsByUserId(Long userId);
}