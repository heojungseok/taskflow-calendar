package com.taskflow.web;

import com.taskflow.calendar.domain.user.Provider;
import com.taskflow.calendar.domain.user.UserRepository;
import com.taskflow.config.SecurityConfig;
import com.taskflow.security.JwtTokenProvider;
import com.taskflow.service.AuthService;
import com.taskflow.web.dto.auth.AuthSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import jakarta.servlet.http.Cookie;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
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
        given(jwtTokenProvider.validateToken(TOKEN)).willReturn(true);
        given(jwtTokenProvider.getUserIdFromToken(TOKEN)).willReturn(7L);
        given(userRepository.isSessionActive(any(), any())).willReturn(true);

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
