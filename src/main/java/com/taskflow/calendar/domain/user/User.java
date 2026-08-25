package com.taskflow.calendar.domain.user;

import lombok.Getter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@EntityListeners(AuditingEntityListener.class)
@Getter
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String email;

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private Provider provider = Provider.GOOGLE;

    @Column(length = 255)
    private String password;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "session_version", nullable = false)
    private int sessionVersion;

    @Column(name = "demo_mutation_count", nullable = false, columnDefinition = "integer default 0")
    private int demoMutationCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at",nullable = false)
    private LocalDateTime updatedAt;

    protected User() {}

    private User(String email, String name) {
        this.email = email;
        this.name = name;
    }

    /**
     * Google OAuth 회원가입 (MVP)
     */
    public static User createGoogleUser(String email, String name) {
        User user = new User();
        user.email = email;
        user.name = name != null && !name.isBlank() ? name : extractNameFromEmail(email);
        user.provider = Provider.GOOGLE;
        user.password = null;
        return user;
    }

    public static User createDemoUser(String id, Instant expiresAt) {
        User user = new User();
        user.email = id + "@demo.taskflow.local";
        user.name = "방문자";
        user.provider = Provider.DEMO;
        user.expiresAt = expiresAt;
        return user;
    }

    public boolean isSessionActive(int tokenVersion, Instant now) {
        return sessionVersion == tokenVersion &&
                (provider == Provider.GOOGLE ||
                        provider == Provider.DEMO && expiresAt != null && expiresAt.isAfter(now));
    }

    public void invalidateSessions() {
        sessionVersion++;
    }

    public void incrementDemoMutationCount() {
        demoMutationCount++;
    }

    /**
     * 이메일에서 이름 추출 (@ 앞부분)
     */
    private static String extractNameFromEmail(String email) {
        if (email == null || !email.contains("@")) {
            return "User";
        }
        return email.substring(0, email.indexOf("@"));
    }

    /**
     * 일반 회원가입 (추후 확장)
     */
    @Deprecated
    public static User of(String email, String name) {
        return new User(email, name);
    }
}
