package com.taskflow.identity.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.taskflow.identity.user.User;
import com.taskflow.identity.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(OutputCaptureExtension.class)
class JwtAuthenticationFilterTest {

    private static final String TOKEN = "secret-session-token";

    private final JwtTokenProvider jwtTokenProvider = mock(JwtTokenProvider.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider, userRepository);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void invalidTokenLogsReasonWithoutTokenValue(CapturedOutput output) throws Exception {
        given(jwtTokenProvider.validateToken(TOKEN)).willReturn(false);

        run();

        assertThat(output)
                .contains("Session token rejected. reason=token_invalid")
                .doesNotContain(TOKEN);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void missingUserLogsReason(CapturedOutput output) throws Exception {
        stubValidToken(3);
        given(userRepository.findById(7L)).willReturn(Optional.empty());

        run();

        assertThat(output).contains("Session token rejected. reason=user_missing");
    }

    @Test
    void revokedVersionIsSeparatedFromExpiredDemoSession(CapturedOutput output) throws Exception {
        stubValidToken(3);
        User revoked = mock(User.class);
        given(revoked.isSessionActive(eq(3), any(Instant.class))).willReturn(false);
        given(revoked.getSessionVersion()).willReturn(4);
        given(userRepository.findById(7L)).willReturn(Optional.of(revoked));

        run();

        assertThat(output).contains("reason=session_revoked").doesNotContain("reason=session_expired");
    }

    @Test
    void expiredDemoSessionLogsExpired(CapturedOutput output) throws Exception {
        stubValidToken(3);
        User expired = mock(User.class);
        given(expired.isSessionActive(eq(3), any(Instant.class))).willReturn(false);
        given(expired.getSessionVersion()).willReturn(3);
        given(userRepository.findById(7L)).willReturn(Optional.of(expired));

        run();

        assertThat(output).contains("reason=session_expired");
    }

    @Test
    void activeSessionAuthenticatesWithoutRejectionLog(CapturedOutput output) throws Exception {
        stubValidToken(3);
        User active = mock(User.class);
        given(active.isSessionActive(eq(3), any(Instant.class))).willReturn(true);
        given(userRepository.findById(7L)).willReturn(Optional.of(active));

        run();

        assertThat(output).doesNotContain("Session token rejected");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo(7L);
    }

    @Test
    void requestWithoutCookieIsNotLogged(CapturedOutput output) throws Exception {
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(output).doesNotContain("Session token rejected");
    }

    private void stubValidToken(int version) {
        given(jwtTokenProvider.validateToken(TOKEN)).willReturn(true);
        given(jwtTokenProvider.getUserIdFromToken(TOKEN)).willReturn(7L);
        given(jwtTokenProvider.getSessionVersion(TOKEN)).willReturn(version);
    }

    private void run() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie("TASKFLOW_SESSION", TOKEN));
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}
