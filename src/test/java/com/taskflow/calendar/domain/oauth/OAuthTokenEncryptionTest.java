package com.taskflow.calendar.domain.oauth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Google 토큰이 DB에 평문으로 남지 않는지 확인한다.
 *
 * <p>엔티티를 통해 읽으면 원문이 나오므로 그것만으로는 암호화 여부를 알 수 없다.
 * 컬럼 값을 네이티브 쿼리로 직접 읽어 평문이 아님을 확인해야 한다.
 * 실제 Postgres(docker: taskflow-postgres)에 붙으며 @DataJpaTest가 각 테스트를 롤백한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class OAuthTokenEncryptionTest {

    private static final long USER_ID = 90101L;
    private static final String ACCESS_TOKEN = "ya29.plaintext-access-token-value";
    private static final String REFRESH_TOKEN = "1//plaintext-refresh-token-value";

    @Autowired
    TestEntityManager em;

    private void persistToken() {
        em.persist(OAuthGoogleToken.create(
                USER_ID, ACCESS_TOKEN, REFRESH_TOKEN,
                LocalDateTime.now().plusHours(1), "openid email"));
        em.flush();
        em.clear();
    }

    private String rawColumn(String column) {
        return (String) em.getEntityManager()
                .createNativeQuery("SELECT " + column + " FROM oauth_google_tokens WHERE user_id = :id")
                .setParameter("id", USER_ID)
                .getSingleResult();
    }

    @Test
    @DisplayName("access/refresh token은 DB 컬럼에 평문으로 저장되지 않는다")
    void tokensAreNotStoredInPlaintext() {
        persistToken();

        assertThat(rawColumn("access_token"))
                .isNotEqualTo(ACCESS_TOKEN)
                .doesNotContain("plaintext-access-token-value");
        assertThat(rawColumn("refresh_token"))
                .isNotEqualTo(REFRESH_TOKEN)
                .doesNotContain("plaintext-refresh-token-value");
    }

    @Test
    @DisplayName("refresh token은 원문으로 복호화된다 - Google 재발급 요청에 그대로 필요하다")
    void tokensRoundTripThroughDecryption() {
        persistToken();

        OAuthGoogleToken found = em.find(OAuthGoogleToken.class, USER_ID);

        assertThat(found.getAccessToken()).isEqualTo(ACCESS_TOKEN);
        assertThat(found.getRefreshToken()).isEqualTo(REFRESH_TOKEN);
    }

    @Test
    @DisplayName("같은 값을 두 번 암호화해도 암호문이 다르다 - IV가 매번 새로 생성된다")
    void encryptionIsNotDeterministic() {
        persistToken();
        String first = rawColumn("access_token");

        OAuthGoogleToken found = em.find(OAuthGoogleToken.class, USER_ID);
        found.updateAccessToken(ACCESS_TOKEN, LocalDateTime.now().plusHours(2));
        em.flush();
        em.clear();

        assertThat(rawColumn("access_token")).isNotEqualTo(first);
    }
}
