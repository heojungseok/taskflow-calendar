package com.taskflow.web;

import com.taskflow.calendar.domain.oauth.GoogleOAuthService;
import com.taskflow.calendar.domain.oauth.dto.AuthorizeUrlResponse;
import com.taskflow.common.ApiResponse;
import com.taskflow.config.GoogleOAuthProperties;
import com.taskflow.security.SecurityContextHelper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/oauth/google")
@RequiredArgsConstructor
public class GoogleOAuthController {

    private final GoogleOAuthProperties properties;
    private final GoogleOAuthService googleOAuthService;

    @GetMapping("/authorize")
    public ApiResponse<AuthorizeUrlResponse> getAuthorizeUrl() {
        // ✅ JWT 있는 시점에 userId 가져오기
        Long userId = SecurityContextHelper.getCurrentUserId();
        // ✅ state에 userId 포함
        String randomPart = UUID.randomUUID().toString();
        String state = userId + "_" + randomPart;

        log.info("Generating authorize URL. userId={}, state={}", userId, state);

        String authorizeUrl = UriComponentsBuilder
                .fromHttpUrl(properties.getAuthorizationUri())
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", properties.getScope())
                .queryParam("state", state)
                .queryParam("access_type", "offline") // refresh_token 받기 위한 필수 값
                .queryParam("prompt", "consent") // 매번 동의 화면 (테스트용)
                .toUriString();

        return  ApiResponse.success(new AuthorizeUrlResponse(authorizeUrl));
    }

    @GetMapping("/callback")
    public ResponseEntity<String> handleCallback(@RequestParam("state") String state, @RequestParam("code") String code) {
        log.info("OAuth callback received. code exists={}, state={}", code != null, state);

        try {

            // ✅ state에서 userId 추출
            String[] parts = state.split("_", 2);
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid state format");
            }

            Long userId = Long.parseLong(parts[0]);
            log.info("Processing OAuth callback. userId={}", userId);

            googleOAuthService.exchangeCodeForToken(code, userId);

            StringBuilder sb = new StringBuilder();
            sb.append("<html>")
                    .append("<head>")
                    .append("<title>Google 연동 성공</title>")
                    .append("<style>")
                    .append("body { font-family: Arial; text-align: center; padding: 50px; }")
                    .append("h1 { color: #4285f4; }")
                    .append("</style>")
                    .append("</head>")
                    .append("<body>")
                    .append("<h1>✅ Google Calendar 연동 성공!</h1>")
                    .append("<p>창을 닫고 애플리케이션으로 돌아가세요.</p>")
                    .append("</body>")
                    .append("</html>");

            String successHtml = sb.toString();

            return ResponseEntity.ok()
                    .header("Content-Type", "text/html; charset=UTF-8")
                    .body(successHtml);
        } catch (Exception e) {
            log.error("OAuth callback failed", e);

            String errorHtml = String.format(
                    "<html>" +
                            "<head>" +
                            "    <title>Google 연동 실패</title>" +
                            "    <style>" +
                            "        body { font-family: Arial; text-align: center; padding: 50px; }" +
                            "        h1 { color: #ea4335; }" +
                            "    </style>" +
                            "</head>" +
                            "<body>" +
                            "    <h1>❌ Google Calendar 연동 실패</h1>" +
                            "    <p>오류: %s</p>" +
                            "    <p>다시 시도해주세요.</p>" +
                            "</body>" +
                            "</html>", escapeHtml(e.getMessage())
            );

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .header(HttpHeaders.CONTENT_TYPE, "text/html; charset=UTF-8")
                    .body(errorHtml);
        }
    }

    private String escapeHtml(String input) {
        if (input == null) return "";
        return input
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
