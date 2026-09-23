package kr.co.cking.auth.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import kr.co.cking.auth.domain.OAuthProvider;
import org.junit.jupiter.api.Test;

class OAuthUserInfoResolverTest {

    private final OAuthUserInfoResolver resolver = new OAuthUserInfoResolver(
            List.of(new GoogleOAuthUserInfoMapper(), new KakaoOAuthUserInfoMapper()));

    @Test
    void resolvesMapperCaseInsensitivelyByRegistrationId() {
        assertThat(resolver.resolve("GOOGLE", Map.of("sub", "google-sub")).provider())
                .isEqualTo(OAuthProvider.GOOGLE);
        assertThat(resolver.resolve("kakao", Map.of("id", 1L)).provider())
                .isEqualTo(OAuthProvider.KAKAO);
    }

    @Test
    void rejectsUnsupportedOrBlankRegistrationId() {
        assertThatThrownBy(() -> resolver.resolve("naver", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(" ", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
