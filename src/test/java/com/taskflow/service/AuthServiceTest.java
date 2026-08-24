package com.taskflow.service;

import com.taskflow.calendar.domain.user.Provider;
import com.taskflow.calendar.domain.user.User;
import com.taskflow.calendar.domain.user.UserRepository;
import com.taskflow.observability.TaskFlowMetrics;
import com.taskflow.security.JwtTokenProvider;
import com.taskflow.web.dto.auth.AuthSession;
import com.taskflow.web.dto.auth.SessionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Long USER_ID = 7L;
    private static final int SESSION_VERSION = 3;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private TaskFlowMetrics metrics;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(userRepository, jwtTokenProvider, metrics);
    }

    @Test
    void createDemoSessionUsesSavedUserVersionAndOneExplicitExpiry() {
        User savedUser = mock(User.class);
        given(savedUser.getId()).willReturn(USER_ID);
        given(savedUser.getSessionVersion()).willReturn(SESSION_VERSION);
        given(savedUser.getProvider()).willReturn(Provider.DEMO);
        given(userRepository.save(any(User.class))).willReturn(savedUser);
        given(jwtTokenProvider.generateToken(eq(USER_ID), eq(SESSION_VERSION), any(Instant.class)))
                .willReturn("jwt");
        Instant before = Instant.now();

        AuthSession session = authService.createDemoSession();

        Instant after = Instant.now();
        ArgumentCaptor<Instant> expiresAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(jwtTokenProvider).generateToken(eq(USER_ID), eq(SESSION_VERSION), expiresAtCaptor.capture());
        Instant expiresAt = expiresAtCaptor.getValue();
        assertThat(expiresAt)
                .isAfterOrEqualTo(before.plusSeconds(86_400))
                .isBeforeOrEqualTo(after.plusSeconds(86_400));
        assertThat(session).isEqualTo(new AuthSession("jwt", USER_ID, Provider.DEMO, expiresAt));

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getExpiresAt().atZone(ZoneId.systemDefault()).toInstant())
                .isEqualTo(expiresAt);
        verify(metrics).demoSessionStarted();
    }

    @Test
    void googleSessionUsesJwtExpiryInsteadOfDatabaseExpiry() {
        User user = mock(User.class);
        Instant tokenExpiresAt = Instant.parse("2026-08-25T03:00:00Z");
        given(user.getProvider()).willReturn(Provider.GOOGLE);
        given(userRepository.findById(USER_ID)).willReturn(Optional.of(user));

        SessionResponse response = authService.getSession(USER_ID, tokenExpiresAt);

        assertThat(response).isEqualTo(new SessionResponse(true, "GOOGLE", tokenExpiresAt));
    }

    @Test
    void missingUserSessionIsAnonymous() {
        given(userRepository.findById(USER_ID)).willReturn(Optional.empty());

        assertThat(authService.getSession(USER_ID, Instant.now()))
                .isEqualTo(SessionResponse.anonymous());
    }
}
