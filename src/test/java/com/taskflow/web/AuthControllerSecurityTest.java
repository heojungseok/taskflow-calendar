package com.taskflow.web;

import com.taskflow.calendar.domain.user.Provider;
import com.taskflow.calendar.domain.user.User;
import com.taskflow.calendar.domain.user.UserRepository;
import com.taskflow.config.SecurityConfig;
import com.taskflow.security.JwtAuthenticationFilter;
import com.taskflow.security.JwtTokenProvider;
import com.taskflow.service.AuthService;
import com.taskflow.web.dto.auth.AuthSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.session.SessionManagementFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, SessionCookieService.class})
class AuthControllerSecurityTest {

    private static final String TOKEN = "session-token";

    @Autowired
    MockMvc mvc;

    @Autowired
    SecurityFilterChain securityFilterChain;

    @MockitoBean
    AuthService authService;

    @MockitoBean
    JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    UserRepository userRepository;

    @Test
    void sessionEndpointBootstrapsCsrfForAnonymousBrowser() throws Exception {
        mvc.perform(get("/api/auth/session"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().secure("XSRF-TOKEN", true))
                .andExpect(jsonPath("$.data.authenticated").value(false));
    }

    @Test
    void jwtAuthenticationRunsAfterSessionManagementAndBeforeAuthorization() {
        List<?> filters = securityFilterChain.getFilters();
        int jwt = filters.indexOf(filters.stream()
                .filter(JwtAuthenticationFilter.class::isInstance)
                .findFirst().orElseThrow());
        int session = filters.indexOf(filters.stream()
                .filter(SessionManagementFilter.class::isInstance)
                .findFirst().orElseThrow());
        int authorization = filters.indexOf(filters.stream()
                .filter(AuthorizationFilter.class::isInstance)
                .findFirst().orElseThrow());

        assertThat(jwt).isGreaterThan(session).isLessThan(authorization);
    }

    @Test
    void demoSessionRequiresCsrfAndSetsHttpOnlyCookie() throws Exception {
        Instant expiresAt = Instant.now().plusSeconds(300);
        given(authService.createDemoSession()).willReturn(
                new AuthSession(TOKEN, 7L, Provider.DEMO, expiresAt));

        mvc.perform(post("/api/auth/demo"))
                .andExpect(status().isForbidden());

        mvc.perform(withCsrf(post("/api/auth/demo")))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("TASKFLOW_SESSION", true))
                .andExpect(cookie().secure("TASKFLOW_SESSION", true))
                .andExpect(cookie().value("TASKFLOW_SESSION", TOKEN))
                .andExpect(jsonPath("$.data.authenticated").value(true))
                .andExpect(jsonPath("$.data.userType").value("DEMO"));
    }

    @Test
    void managementHealthAndPrometheusAllowAnonymousScraping() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isNotFound());
    }

    @Test
    void logoutRequiresCsrfAndClearsSessionCookie() throws Exception {
        User user = mock(User.class);
        given(jwtTokenProvider.validateToken(TOKEN)).willReturn(true);
        given(jwtTokenProvider.getUserIdFromToken(TOKEN)).willReturn(7L);
        given(jwtTokenProvider.getSessionVersion(TOKEN)).willReturn(3);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        given(user.isSessionActive(eq(3), any(LocalDateTime.class))).willReturn(true);

        mvc.perform(post("/api/auth/logout").cookie(new Cookie("TASKFLOW_SESSION", TOKEN)))
                .andExpect(status().isForbidden());

        MockHttpServletRequestBuilder logout = withCsrf(post("/api/auth/logout"));
        mvc.perform(logout.cookie(new Cookie("TASKFLOW_SESSION", TOKEN)))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("TASKFLOW_SESSION", 0));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult bootstrap = mvc.perform(get("/api/auth/session")).andReturn();
        Cookie csrf = bootstrap.getResponse().getCookie("XSRF-TOKEN");
        return request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
    }
}
