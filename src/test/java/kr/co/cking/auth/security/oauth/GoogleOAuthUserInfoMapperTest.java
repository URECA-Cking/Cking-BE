package kr.co.cking.auth.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthProvider;
import org.junit.jupiter.api.Test;

class GoogleOAuthUserInfoMapperTest {

    private final GoogleOAuthUserInfoMapper mapper = new GoogleOAuthUserInfoMapper();

    @Test
    void mapsGoogleSubNameAndEmail() {
        OAuthUserInfo userInfo = mapper.map(Map.of(
                "sub", "google-sub-123",
                "name", "Google 사용자",
                "email", "google@example.com"
        ));

        assertThat(userInfo).isEqualTo(new OAuthUserInfo(
                OAuthProvider.GOOGLE, "google-sub-123", "google@example.com", "Google 사용자"));
    }

    @Test
    void rejectsMissingOrBlankGoogleSub() {
        assertThatThrownBy(() -> mapper.map(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.map(Map.of("sub", "  ")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
