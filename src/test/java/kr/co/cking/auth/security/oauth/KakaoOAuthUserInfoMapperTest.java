package kr.co.cking.auth.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import kr.co.cking.auth.application.model.OAuthUserInfo;
import kr.co.cking.auth.domain.OAuthProvider;
import org.junit.jupiter.api.Test;

class KakaoOAuthUserInfoMapperTest {

    private final KakaoOAuthUserInfoMapper mapper = new KakaoOAuthUserInfoMapper();

    @Test
    void mapsNumericKakaoIdNicknameAndEmail() {
        OAuthUserInfo userInfo = mapper.map(Map.of(
                "id", 123456789L,
                "properties", Map.of("nickname", "Kakao 사용자"),
                "kakao_account", Map.of("email", "kakao@example.com")
        ));

        assertThat(userInfo).isEqualTo(new OAuthUserInfo(
                OAuthProvider.KAKAO, "123456789", "kakao@example.com", "Kakao 사용자"));
    }

    @Test
    void allowsMissingOptionalProfileValues() {
        OAuthUserInfo userInfo = mapper.map(Map.of("id", "123456789"));

        assertThat(userInfo).isEqualTo(new OAuthUserInfo(OAuthProvider.KAKAO, "123456789", null, null));
    }

    @Test
    void rejectsMissingKakaoIdOrInvalidNestedAttributes() {
        assertThatThrownBy(() -> mapper.map(Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.map(Map.of("id", 1L, "properties", "nickname")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonIntegerOrNonPositiveKakaoIdBeforeMemberConnection() {
        assertThatThrownBy(() -> mapper.map(Map.of("id", "abc")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.map(Map.of("id", 1.5d)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> mapper.map(Map.of("id", 0L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
